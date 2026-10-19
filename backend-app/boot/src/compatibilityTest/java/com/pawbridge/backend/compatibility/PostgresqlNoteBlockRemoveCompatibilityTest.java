package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock;
import com.pawbridge.backend.community.internal.note.application.NoteBlockRemovalRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Owned synthetic writes only; original JpaTM and remote-User thread boundary stay distinct from candidate JdbcTM. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteBlockRemoveCompatibilityTest {
    private static final long CALLER=9_978_101L,TARGET=9_978_102L,THIRD=9_978_103L;
    private static final Set<Long> NAMESPACE=Set.of(CALLER,TARGET,THIRD);
    private final Set<Long> users=new LinkedHashSet<>(),members=new LinkedHashSet<>(),pending=new LinkedHashSet<>();
    private final Set<Block> blocks=new LinkedHashSet<>();
    private final AtomicInteger contactCalls=new AtomicInteger();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader,writer;
    private JdbcTemplate readJdbc,writeJdbc;
    private ContactMemberQuery contacts;
    private JdbcNoteBlockRemoval removal;
    private RemoveNoteBlock candidate;
    private F24LegacyNoteBlockOracle original;
    private String baseline;
    private boolean namespaceOwned;

    @BeforeAll void openOnlyOwnedFixtureAndTheExistingTwoSingleConnectionShapes()throws Exception{
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        owner=F3PostgresqlFixture.open();verifyOwnership();requireWriterPrivileges();
        reader=pool("f24-note-block-reader",true,owner.reads().applicationUser(),owner.reads().applicationPassword());
        writer=pool("f24-note-block-writer",false,F3PostgresqlFixture.WRITER,owner.writerPassword());
        readJdbc=new JdbcTemplate(reader);readJdbc.setQueryTimeout(3);
        writeJdbc=new JdbcTemplate(writer);writeJdbc.setQueryTimeout(5);
        var readManager=new JdbcTransactionManager(reader);readManager.setEnforceReadOnly(true);
        var readTx=new TransactionTemplate(readManager);readTx.setReadOnly(true);readTx.setTimeout(3);
        var single=new JdbcContactMemberQuery(readJdbc,readTx);
        contacts=id->{
            assertThat(id).isEqualTo(CALLER);contactCalls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
            assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isOne();
            try{return single.get(id);}
            finally{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        };
        var tx=new TransactionTemplate(new JdbcTransactionManager(writer));tx.setTimeout(15);
        removal=new JdbcNoteBlockRemoval(writeJdbc,tx);
        candidate=new RemoveNoteBlock(contacts,removal);
        original=new F24LegacyNoteBlockOracle(owner,reader);returned();
    }
    private HikariDataSource pool(String name,boolean readOnly,String user,String password){
        var config=new HikariConfig();config.setJdbcUrl(owner.reads().jdbcUrl());config.setUsername(user);config.setPassword(password);
        config.setPoolName(name);config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setReadOnly(readOnly);
        config.setConnectionTimeout(2_000);config.setValidationTimeout(1_000);config.addDataSourceProperty("socketTimeout","20");
        config.addDataSourceProperty("ApplicationName",name);config.setConnectionInitSql("SET statement_timeout='18s'; SET lock_timeout='10s'");
        return new HikariDataSource(config);
    }
    @BeforeEach void reserveOnlyAnEmptyOwnedNamespaceAndSnapshotAllSixteenTables()throws Exception{
        namespaceOwned=false;baseline=null;verifyOwnership();returned();contactCalls.set(0);
        for(long id:NAMESPACE){
            assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)).isZero();
        }
        baseline=snapshot();namespaceOwned=true;
    }
    @AfterEach void removeOnlySuccessfulOwnedKeysAndRestoreTheEntireBaseline()throws Exception{
        if(owner==null||!namespaceOwned)return;
        verifyOwnership();trackCreatedMembers();
        for(Block pair:blocks)execute("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id="+pair.first()+" AND blocked_id="+pair.second());
        deleteIds("pawbridge_community.private_note_members","member_id",members);
        deleteIds("pawbridge_user.contact_deletions","user_id",pending);deleteIds("pawbridge_user.users","user_id",users);
        blocks.clear();members.clear();pending.clear();users.clear();
        if(baseline!=null)assertThat(snapshot()).isEqualTo(baseline);namespaceOwned=false;returned();
    }
    @AfterAll void closeOnlyTheOracleAndTheseTwoPools(){
        try{if(original!=null)original.close();}
        finally{if(reader!=null)reader.close();if(writer!=null)writer.close();}
    }

    @Test void givenOutgoingReverseAndOtherBlocks__whenOriginalAndCandidateRemoveTheirOwnOutgoingPair__thenBothPreserveEveryOtherRowAndRepeatSucceeds()throws Exception{
        user(CALLER);user(TARGET);user(THIRD);block(CALLER,TARGET);block(TARGET,CALLER);block(THIRD,CALLER);
        original.remove(CALLER,TARGET);returned();String expected=snapshot();
        assertThat(countBlock(CALLER,TARGET)).isZero();assertThat(countBlock(TARGET,CALLER)).isOne();assertThat(countBlock(THIRD,CALLER)).isOne();
        block(CALLER,TARGET);candidate.remove(CALLER,TARGET);returned();assertThat(snapshot()).isEqualTo(expected);
        assertThat(contactCalls).hasValue(1);candidate.remove(CALLER,TARGET);returned();assertThat(snapshot()).isEqualTo(expected);assertThat(contactCalls).hasValue(2);
    }
    @ParameterizedTest @ValueSource(strings={"absent-block","self","missing-target","pending-target"})
    void givenAnAllowedNoOpOrUnavailableTarget__whenUnblocking__thenOriginalAndCandidateKeepTheSameMemberInsertAndNoTargetLookup(String kind)throws Exception{
        user(CALLER);if(kind.equals("absent-block"))user(TARGET);if(kind.equals("pending-target")){user(TARGET);pending(TARGET);}
        long target=kind.equals("self")?CALLER:TARGET;
        original.remove(CALLER,target);trackCreatedMembers();returned();String expected=snapshot();
        deleteIds("pawbridge_community.private_note_members","member_id",members);members.clear();
        candidate.remove(CALLER,target);trackCreatedMembers();returned();
        assertThat(snapshot()).isEqualTo(expected);assertThat(contactCalls).hasValue(1);
        assertThat(members).containsExactlyInAnyOrderElementsOf(target==CALLER?Set.of(CALLER):Set.of(CALLER,TARGET));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void givenMissingOrPendingCurrentMember__whenTheActiveGuardRunsAfterMemberInsertAndLocks__thenBothRollBackThoseNewMembers(boolean pendingCurrent)throws Exception{
        if(pendingCurrent){user(CALLER);pending(CALLER);}String before=snapshot();
        assertThatThrownBy(()->original.remove(CALLER,TARGET)).isInstanceOf(ResponseStatusException.class)
                .satisfies(failure->assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(403));
        returned();assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(()->candidate.remove(CALLER,TARGET)).isInstanceOf(NoteBlockRemovalRejectedException.class);
        returned();assertThat(snapshot()).isEqualTo(before);assertThat(contactCalls).hasValue(1);
    }
    @Test void givenCurrentUserSelectDenied__whenBothUserBoundariesFail__thenTheOuterWriterRollsBackAndBothConnectionsReturnBeforeRecovery()throws Exception{
        user(CALLER);String before=snapshot();
        assertThat(scalar("SELECT has_column_privilege('pawbridge_f2_reader','pawbridge_user.users','nickname','SELECT')::int")).isOne();
        execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try{
            assertThatThrownBy(()->original.remove(CALLER,TARGET)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(failure->assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(503));
            returned();assertThat(snapshot()).isEqualTo(before);
            assertThatThrownBy(()->candidate.remove(CALLER,TARGET)).isInstanceOf(ContactMemberUnavailableException.class);
            returned();assertThat(snapshot()).isEqualTo(before);
        }finally{verifyOwnership();execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");}
        candidate.remove(CALLER,TARGET);trackCreatedMembers();returned();assertThat(members).containsExactlyInAnyOrder(CALLER,TARGET);
    }
    @ParameterizedTest @ValueSource(strings={"lock","delete"})
    void givenOnlyTheNecessaryWriterPrivilegeDenied__whenRemoving__thenNoPartialMemberOrBlockChangeAndFinallyGrantRestoresSuccess(String phase)throws Exception{
        user(CALLER);block(CALLER,TARGET);String before=snapshot();
        requireWriterPrivileges();
        String revoke=phase.equals("lock")?"UPDATE(member_id) ON pawbridge_community.private_note_members":"DELETE ON pawbridge_community.private_note_blocks";
        execute("REVOKE "+revoke+" FROM pawbridge_f3_writer");
        try{assertThatThrownBy(()->candidate.remove(CALLER,TARGET)).isInstanceOf(DataAccessException.class);returned();assertThat(snapshot()).isEqualTo(before);}
        finally{verifyOwnership();execute("GRANT "+revoke+" TO pawbridge_f3_writer");}
        candidate.remove(CALLER,TARGET);returned();assertThat(countBlock(CALLER,TARGET)).isZero();
    }
    @Test void givenDifferentTransactionManagersAndDatasourceResources__whenTheUserReadEndsInsideTheWriter__thenTheWriterRemainsActiveAndTheReaderReturnsBeforeDelete()throws Exception{
        user(CALLER);
        candidate.remove(CALLER,TARGET);trackCreatedMembers();returned();
        assertThat(contactCalls).hasValue(1);assertThat(members).containsExactlyInAnyOrder(CALLER,TARGET);
        assertThat(writeJdbc.getQueryTimeout()).isEqualTo(5);assertThat(writer.getMaximumPoolSize()).isOne();assertThat(reader.getMaximumPoolSize()).isOne();
    }
    @Test void givenAnOwnedDeferredCommitRejection__whenOriginalJpaAndCandidateJdbcCommit__thenCompareTheObservableFailureFamilyAndPreserveRows()throws Exception{
        user(CALLER);block(CALLER,TARGET);String before=snapshot();
        String name="f24_commit_"+F3PostgresqlFixture.required("GITHUB_RUN_ID")+"_"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        assertThat(name).matches("[a-z0-9_]{1,60}");
        assertThat(scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='pawbridge_f17_fixture' AND p.proname='"+name+"'")).isZero();
        assertThat(scalar("SELECT count(*) FROM pg_trigger WHERE tgname='"+name+"'")).isZero();
        execute("CREATE FUNCTION pawbridge_f17_fixture."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.member_id="+CALLER+" AND OLD.blocked_id="+TARGET+" THEN RAISE EXCEPTION USING ERRCODE='23514', CONSTRAINT='f24_owned_commit_rejection', MESSAGE='synthetic commit rejection'; END IF; RETURN NULL; END; $$; CREATE CONSTRAINT TRIGGER "+name+" AFTER DELETE ON pawbridge_community.private_note_blocks DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f17_fixture."+name+"()");
        try{
            RuntimeException referenceFailure=failure(()->original.remove(CALLER,TARGET));returned();assertThat(snapshot()).isEqualTo(before);
            RuntimeException candidateFailure=failure(()->candidate.remove(CALLER,TARGET));returned();assertThat(snapshot()).isEqualTo(before);
            requireOwnedCommitRejection(referenceFailure);requireOwnedCommitRejection(candidateFailure);
            assertThat(classification(candidateFailure)).isEqualTo(classification(referenceFailure));
        }finally{
            verifyOwnership();execute("DROP TRIGGER "+name+" ON pawbridge_community.private_note_blocks; DROP FUNCTION pawbridge_f17_fixture."+name+"()");
        }
        candidate.remove(CALLER,TARGET);returned();assertThat(countBlock(CALLER,TARGET)).isZero();
    }
    @Test void givenAnotherConnectionHoldsTheSecondSortedMemberLock__whenTheSingleWriterUnblocks__thenObserveAnActualPgLockWaitBeforeUserReadAndCompleteAfterRelease()throws Exception{
        user(CALLER);block(CALLER,TARGET);var executor=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
        try(Connection blocker=admin()){
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id=? FOR UPDATE")){
                statement.setLong(1,TARGET);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
            }
            var task=executor.submit(()->{started.countDown();candidate.remove(CALLER,TARGET);returned();});
            try{
                assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);boolean waiting=false;
                while(System.nanoTime()<deadline){
                    if(scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='f24-note-block-writer' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0")>0){waiting=true;break;}
                    Thread.sleep(20);
                }
                assertThat(waiting).isTrue();assertThat(contactCalls).hasValue(0);assertThat(task.isDone()).isFalse();
                blocker.commit();task.get(8,TimeUnit.SECONDS);assertThat(countBlock(CALLER,TARGET)).isZero();assertThat(contactCalls).hasValue(1);
            }finally{if(!blocker.getAutoCommit())blocker.rollback();if(!task.isDone())task.cancel(true);}
        }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        returned();
    }
    @Test @Order(Integer.MAX_VALUE)
    void givenTheOriginalIssuerAndOwnedKeyFile__whenAddingOnlyTheUsedF24Principal__thenPreserveAllEarlierKeysAndRestrictivePermissions()throws Exception{
        verifyOwnership();String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path path=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(path).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));
        assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper();Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(path),new TypeReference<Map<String,String>>(){}));var old=Set.copyOf(tokens.keySet());
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var user=User.builder().userId(9_978_001L).email("f24-token@fixture.invalid").name("F24 synthetic").nickname("F24token").provider("GOOGLE").role(Role.ROLE_USER).build();
        String token=new JwtProvider(secret,600_000,600_000).createAccessToken(user);
        assertThat(new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).authenticate("Bearer "+token)).isEqualTo(9_978_001L);
        tokens.put("F24_CALLER",token);assertThat(tokens.keySet()).containsAll(old);Files.writeString(path,mapper.writeValueAsString(tokens));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));returned();
    }
    private RuntimeException failure(Runnable action){try{action.run();}catch(RuntimeException failure){return failure;}throw new AssertionError("Expected an owned synthetic failure");}
    private String classification(RuntimeException failure){return failure instanceof DataAccessException?"storage":"generic";}
    private void requireOwnedCommitRejection(Throwable failure){
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        var queue=new java.util.ArrayDeque<Throwable>();queue.add(failure);boolean matched=false;
        while(!queue.isEmpty()&&seen.size()<32){
            Throwable current=queue.removeFirst();if(!seen.add(current))continue;
            if(current instanceof PSQLException postgres&&"23514".equals(postgres.getSQLState())&&postgres.getServerErrorMessage()!=null
                    &&"f24_owned_commit_rejection".equals(postgres.getServerErrorMessage().getConstraint()))matched=true;
            if(current.getCause()!=null)queue.add(current.getCause());
            if(current instanceof SQLException sql&&sql.getNextException()!=null)queue.add(sql.getNextException());
        }
        assertThat(matched).as("Each manager must fail from the owned deferred constraint at commit").isTrue();
    }
    private void user(long id)throws SQLException{
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")){
            s.setLong(1,id);s.setString(2,"f24-"+id+"@fixture.invalid");s.setString(3,"F24 synthetic");s.setString(4,"F24nickname"+id);s.setString(5,"GOOGLE");s.setString(6,"ROLE_USER");s.executeUpdate();users.add(id);
        }
    }
    private void pending(long id)throws SQLException{execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES("+id+")");pending.add(id);}
    private void member(long id)throws SQLException{if(!members.contains(id)){execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES("+id+")");members.add(id);}}
    private void block(long first,long second)throws SQLException{
        member(first);member(second);execute("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES("+first+","+second+",'2031-01-01T00:00:00Z')");blocks.add(new Block(first,second));
    }
    private long countBlock(long first,long second)throws SQLException{return scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+first+" AND blocked_id="+second);}
    private void trackCreatedMembers()throws SQLException{for(long id:NAMESPACE)if(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)==1)members.add(id);}
    private void deleteIds(String table,String column,Set<Long> keys)throws SQLException{
        if(!Set.of("pawbridge_community.private_note_members.member_id","pawbridge_user.contact_deletions.user_id","pawbridge_user.users.user_id").contains(table+"."+column))throw new IllegalArgumentException("Only owned key cleanup");
        for(long id:keys)execute("DELETE FROM "+table+" WHERE "+column+"="+id);
    }
    private Connection admin()throws SQLException{return owner.admin();}
    private void verifyOwnership()throws SQLException{
        requireStageReceipt();
        try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery("SELECT value,owner FROM pawbridge_f17_fixture.marker")){
            assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("pawbridge-f17-ci-only-v1");
            String expected=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            assertThat(rows.getString(2)).isEqualTo(expected);assertThat(F3PostgresqlFixture.required("F17_CI_OWNER")).isEqualTo(expected);assertThat(rows.next()).isFalse();
        }
    }
    private void requireStageReceipt(){
        String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        String raw=System.getProperty("f24.fixture.receipt");
        if(raw==null)throw new IllegalStateException("F24 stage receipt property is required");
        Path path=Path.of(raw).toAbsolutePath().normalize();
        assertThat(path.getFileName().toString()).isEqualTo("f24-fixture-write.txt");
        assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();
        String expected="owner="+run+"\nsource_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\n"
                +"fixture=F23 notes8/mailboxes6 plus member-lock/outgoing-block writer\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\n"
                +"writer_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE\nnew_sequence_acl=0\n";
        try{assertThat(path.toRealPath()).isEqualTo(path);assertThat(Files.readString(path)).isEqualTo(expected);}
        catch(java.io.IOException failure){throw new IllegalStateException("F24 stage receipt cannot be verified");}
    }
    private void requireWriterPrivileges()throws SQLException{
        assertThat(scalar("SELECT (has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','SELECT') "
                +"AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','INSERT') "
                +"AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','UPDATE') "
                +"AND has_table_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','DELETE'))::int")).isOne();
    }
    private void execute(String sql)throws SQLException{try(Connection c=admin();var s=c.createStatement()){s.execute(sql);}}
    private long scalar(String sql)throws SQLException{try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    private String snapshot() throws SQLException {
        String sql = "SELECT md5(json_build_object("
                + "'private_notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_community.private_notes n),"
                + "'mailboxes',(SELECT COALESCE(json_agg(m ORDER BY member_id,note_id),'[]') FROM pawbridge_community.private_note_mailboxes m),"
                + "'pending',(SELECT COALESCE(json_agg(d ORDER BY user_id),'[]') FROM pawbridge_user.contact_deletions d),"
                + "'members',(SELECT COALESCE(json_agg(m ORDER BY member_id),'[]') FROM pawbridge_community.private_note_members m),"
                + "'blocks',(SELECT COALESCE(json_agg(b ORDER BY member_id,blocked_id),'[]') FROM pawbridge_community.private_note_blocks b),"
                + "'visits',(SELECT COALESCE(json_agg(v ORDER BY visit_date),'[]') FROM pawbridge_community.daily_visit_count v),"
                + "'videos',(SELECT COALESCE(json_agg(v ORDER BY id),'[]') FROM pawbridge_community.home_videos v),"
                + "'board',(SELECT COALESCE(json_agg(b ORDER BY id),'[]') FROM pawbridge_community.home_video_board b),"
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'favorites',(SELECT COALESCE(json_agg(f ORDER BY favorite_id),'[]') FROM pawbridge_user.favorites f),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_community.comments c),"
                + "'f4comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_f4_fixture.comments c),"
                + "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_f4_fixture.private_notes n),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))::text)";
        try (Connection c = admin(); var s = c.createStatement(); var rows = s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private void returned(){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        if(reader!=null){assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();}
        if(writer!=null){assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();}
        if(original!=null)assertThat(original.isResourceBound()).isFalse();
    }
    private record Block(long first,long second){}
}
