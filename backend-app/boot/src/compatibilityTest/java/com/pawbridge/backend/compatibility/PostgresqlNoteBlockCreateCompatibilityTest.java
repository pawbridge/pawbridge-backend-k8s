package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock;
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreationRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Owned hosted fixture only. Source JpaTM/User thread and candidate writer JdbcTM/User RO TM are distinct. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteBlockCreateCompatibilityTest {
    private static final long CALLER=9_979_101L,TARGET=9_979_102L,THIRD=9_979_103L;
    private static final String SOURCE="f06a72a06bd26662c647bde780dfc7a27bf7349c";
    private static final Instant FIRST=Instant.parse("2031-01-01T00:00:00.123456Z"), LATER=Instant.parse("2031-01-02T00:00:00.654321Z");
    private static final Set<Long> NAMESPACE=Set.of(CALLER,TARGET,THIRD);
    private final Set<Long> users=new LinkedHashSet<>(),members=new LinkedHashSet<>(),pending=new LinkedHashSet<>();
    private final Set<Block> blocks=new LinkedHashSet<>();
    private final List<Long> contactIds=new CopyOnWriteArrayList<>();
    private final SampleClock candidateClock=new SampleClock(),originalClock=new SampleClock();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader,writer;
    private JdbcTemplate readJdbc,writeJdbc;
    private CreateNoteBlock candidate;
    private F25LegacyNoteBlockOracle original;
    private String baseline;
    private boolean namespaceOwned;

    @BeforeAll void openOnlyTheOwnedF25StageAndTheExistingTwoSingleConnectionShapes()throws Exception{
        assertThat(System.getProperty("user.timezone")).isIn("UTC","Asia/Seoul");
        owner=F3PostgresqlFixture.open();verifyOwnership();requireWriterPrivileges();
        try{
            reader=pool("f25-note-block-reader",true,owner.reads().applicationUser(),owner.reads().applicationPassword());
            writer=pool("f25-note-block-writer",false,F3PostgresqlFixture.WRITER,owner.writerPassword());
            readJdbc=new JdbcTemplate(reader);readJdbc.setQueryTimeout(3);
            writeJdbc=new JdbcTemplate(writer);writeJdbc.setQueryTimeout(5);
            var readManager=new JdbcTransactionManager(reader);readManager.setEnforceReadOnly(true);
            var readTx=new TransactionTemplate(readManager);readTx.setReadOnly(true);readTx.setTimeout(3);
            var single=new JdbcContactMemberQuery(readJdbc,readTx);
            ContactMemberQuery contacts=id->{
                assertThat(id).isIn(CALLER,TARGET);contactIds.add(id);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
                assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isOne();
                try{return single.get(id);}
                finally{
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
                    assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                    assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
                    assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isOne();
                }
            };
            var writeTx=new TransactionTemplate(new JdbcTransactionManager(writer));writeTx.setTimeout(15);
            candidate=new CreateNoteBlock(contacts,new JdbcNoteBlockCreation(writeJdbc,writeTx),candidateClock);
            original=new F25LegacyNoteBlockOracle(owner,reader,originalClock);
            // Metadata/JPA bootstrap above is not counted as an endpoint or User request.
            returned();
        }catch(Exception|Error failure){closeOnlyTheOracleAndTheseTwoPools();throw failure;}
    }
    private HikariDataSource pool(String name,boolean readOnly,String user,String password){
        var config=new HikariConfig();config.setJdbcUrl(owner.reads().jdbcUrl());config.setUsername(user);config.setPassword(password);
        config.setPoolName(name);config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setReadOnly(readOnly);
        config.setConnectionTimeout(2_000);config.setValidationTimeout(1_000);config.addDataSourceProperty("socketTimeout","20");
        config.addDataSourceProperty("ApplicationName",name);config.setConnectionInitSql("SET statement_timeout='18s'; SET lock_timeout='10s'");
        return new HikariDataSource(config);
    }
    @BeforeEach void reserveOnlyAnEmptyOwnedNamespaceAndSnapshotAllSixteenTables()throws Exception{
        namespaceOwned=false;baseline=null;verifyOwnership();requireWriterPrivileges();returned();resetObservations(FIRST);
        for(long id:NAMESPACE){
            assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+id+" OR blocked_id="+id)).isZero();
        }
        baseline=snapshot();namespaceOwned=true;
    }
    @AfterEach void removeOnlySuccessfullyCreatedOwnedKeysAndRestoreTheEntireBaseline()throws Exception{
        if(owner==null||!namespaceOwned)return;
        verifyOwnership();returned();trackCreatedKeys();
        for(Block pair:blocks)execute("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id="+pair.first()+" AND blocked_id="+pair.second());
        deleteIds("pawbridge_community.private_note_members","member_id",members);
        deleteIds("pawbridge_user.contact_deletions","user_id",pending);deleteIds("pawbridge_user.users","user_id",users);
        blocks.clear();members.clear();pending.clear();users.clear();
        assertThat(snapshot()).isEqualTo(baseline);namespaceOwned=false;returned();
    }
    @AfterAll void closeOnlyTheOracleAndTheseTwoPools(){
        try{if(original!=null)original.close();}
        finally{try{if(reader!=null)reader.close();}finally{if(writer!=null)writer.close();}}
    }

    @Test void givenTwoActiveUsersAndNoMemberRows__whenOriginalJpaAndCandidateJdbcCreate__thenKeepTheSameEntireSnapshotAndReleaseEachUserReadInsideTheWriter()throws Exception{
        user(CALLER);user(TARGET);
        original.create(CALLER,TARGET);returned();assertThat(original.contactIds()).containsExactly(CALLER,TARGET);assertThat(originalClock.calls).hasValue(1);
        assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);String expected=snapshot();
        clearOwnedCommunityRows();resetObservations(FIRST);
        candidate.create(CALLER,TARGET);returned();assertThat(contactIds).containsExactly(CALLER,TARGET);assertThat(candidateClock.calls).hasValue(1);
        assertThat(snapshot()).isEqualTo(expected);assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);
        assertThat(writeJdbc.getQueryTimeout()).isEqualTo(5);assertThat(writer.getMaximumPoolSize()).isOne();assertThat(reader.getMaximumPoolSize()).isOne();
    }
    @Test void givenAnExistingOutgoingPairAndReverseAndOtherPairs__whenBothRepeatWithALaterClock__thenCheckBothMembersAgainWithoutUpdatingAnyRow()throws Exception{
        user(CALLER);user(TARGET);user(THIRD);block(CALLER,TARGET,FIRST);block(TARGET,CALLER,FIRST);block(THIRD,TARGET,FIRST);
        String before=snapshot();resetObservations(LATER);
        original.create(CALLER,TARGET);returned();assertThat(snapshot()).isEqualTo(before);assertThat(original.contactIds()).containsExactly(CALLER,TARGET);assertThat(originalClock.calls).hasValue(1);
        candidate.create(CALLER,TARGET);returned();assertThat(snapshot()).isEqualTo(before);assertThat(contactIds).containsExactly(CALLER,TARGET);assertThat(candidateClock.calls).hasValue(1);
        assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);assertThat(countBlock(TARGET,CALLER)).isOne();assertThat(countBlock(THIRD,TARGET)).isOne();
    }
    @ParameterizedTest @ValueSource(strings={"missing-current","pending-current","missing-target","pending-target"})
    void givenAMissingOrPendingCurrentOrTarget__whenBothCheckAfterMemberLocks__thenKeepCurrentFirstAndRollBackAllNewMembersBeforeClock(String kind)throws Exception{
        boolean current=kind.endsWith("current"),pendingMember=kind.startsWith("pending");
        if(!current)user(CALLER);if(current&&pendingMember){user(CALLER);pending(CALLER);}if(current)user(TARGET);
        if(!current&&pendingMember){user(TARGET);pending(TARGET);}String before=snapshot();
        assertThatThrownBy(()->original.create(CALLER,TARGET)).isInstanceOf(ResponseStatusException.class)
                .satisfies(failure->assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(403));
        returned();assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(()->candidate.create(CALLER,TARGET)).isInstanceOf(NoteBlockCreationRejectedException.class)
                .satisfies(failure->assertThat(((NoteBlockCreationRejectedException)failure).reason()).isEqualTo(NoteBlockCreationRejectedException.Reason.INACTIVE));
        returned();assertThat(snapshot()).isEqualTo(before);
        List<Long> expected=current?List.of(CALLER):List.of(CALLER,TARGET);
        assertThat(original.contactIds()).containsExactlyElementsOf(expected);assertThat(contactIds).containsExactlyElementsOf(expected);
        assertThat(originalClock.calls).hasValue(0);assertThat(candidateClock.calls).hasValue(0);
    }
    @Test void givenCurrentUserNicknameSelectDenied__whenBothUserBoundariesFail__thenRollBackNewMembersAndReturnBothConnectionsBeforeRecovery()throws Exception{
        user(CALLER);user(TARGET);String before=snapshot();requireNicknamePrivilege();
        execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try{
            assertThatThrownBy(()->original.create(CALLER,TARGET)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(failure->assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(503));
            returned();assertThat(snapshot()).isEqualTo(before);
            assertThatThrownBy(()->candidate.create(CALLER,TARGET)).isInstanceOf(ContactMemberUnavailableException.class);
            returned();assertThat(snapshot()).isEqualTo(before);assertThat(contactIds).containsExactly(CALLER);assertThat(original.contactIds()).containsExactly(CALLER);
            assertThat(candidateClock.calls).hasValue(0);assertThat(originalClock.calls).hasValue(0);
        }finally{verifyOwnership();execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");requireNicknamePrivilege();requireWriterPrivileges();}
        resetObservations(FIRST);candidate.create(CALLER,TARGET);returned();assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);
    }
    @ParameterizedTest @ValueSource(strings={"lock","insert"})
    void givenOnlyANecessaryWriterPrivilegeDenied__whenCandidateCreates__thenRollBackEveryNewMemberAndRestoreSuccessInFinally(String phase)throws Exception{
        user(CALLER);user(TARGET);String before=snapshot();requireWriterPrivileges();
        String privilege=phase.equals("lock")?"UPDATE(member_id) ON pawbridge_community.private_note_members":"INSERT(member_id,blocked_id,created_at) ON pawbridge_community.private_note_blocks";
        execute("REVOKE "+privilege+" FROM pawbridge_f3_writer");
        try{
            assertThatThrownBy(()->candidate.create(CALLER,TARGET)).isInstanceOf(DataAccessException.class);returned();assertThat(snapshot()).isEqualTo(before);
            if(phase.equals("lock")){assertThat(contactIds).isEmpty();assertThat(candidateClock.calls).hasValue(0);}
            else{assertThat(contactIds).containsExactly(CALLER,TARGET);assertThat(candidateClock.calls).hasValue(1);}
        }finally{verifyOwnership();execute("GRANT "+privilege+" TO pawbridge_f3_writer");requireWriterPrivileges();requireNicknamePrivilege();}
        resetObservations(FIRST);candidate.create(CALLER,TARGET);returned();assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);
    }
    @Test void givenAnOwnedDeferredInsertCommitRejection__whenOriginalJpaAndCandidateJdbcCommit__thenCompareTheirActualFailureFamilyFromTheSameConstraintAndRollBackAllRows()throws Exception{
        user(CALLER);user(TARGET);String before=snapshot();
        String name="f25_commit_"+F3PostgresqlFixture.required("GITHUB_RUN_ID")+"_"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");assertThat(name).matches("[a-z0-9_]{1,60}");
        assertThat(scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='pawbridge_f17_fixture' AND p.proname='"+name+"'")).isZero();
        assertThat(scalar("SELECT count(*) FROM pg_trigger WHERE tgname='"+name+"'")).isZero();boolean functionCreated=false,triggerCreated=false;
        try{
            execute("CREATE FUNCTION pawbridge_f17_fixture."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.member_id="+CALLER+" AND NEW.blocked_id="+TARGET+" THEN RAISE EXCEPTION USING ERRCODE='23514', CONSTRAINT='f25_owned_commit_rejection', MESSAGE='synthetic commit rejection'; END IF; RETURN NULL; END; $$");functionCreated=true;
            execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER INSERT ON pawbridge_community.private_note_blocks DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f17_fixture."+name+"()");triggerCreated=true;
            RuntimeException referenceFailure=failure(()->original.create(CALLER,TARGET));returned();assertThat(snapshot()).isEqualTo(before);
            RuntimeException candidateFailure=failure(()->candidate.create(CALLER,TARGET));returned();assertThat(snapshot()).isEqualTo(before);
            requireOwnedCommitRejection(referenceFailure);requireOwnedCommitRejection(candidateFailure);
            assertThat(classification(candidateFailure)).isEqualTo(classification(referenceFailure));
            assertThat(original.contactIds()).containsExactly(CALLER,TARGET);assertThat(contactIds).containsExactly(CALLER,TARGET);
            assertThat(originalClock.calls).hasValue(1);assertThat(candidateClock.calls).hasValue(1);
        }finally{
            verifyOwnership();
            try{if(triggerCreated)execute("DROP TRIGGER "+name+" ON pawbridge_community.private_note_blocks");}
            finally{if(functionCreated)execute("DROP FUNCTION pawbridge_f17_fixture."+name+"()");}
        }
        resetObservations(FIRST);candidate.create(CALLER,TARGET);returned();assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);
    }
    @Test void givenAnotherConnectionHoldsTheSecondSortedMemberLock__whenTheSingleWriterCreates__thenObserveAnActualPgWaitBeforeEitherUserOrClockAndFinishAfterRelease()throws Exception{
        user(CALLER);user(TARGET);member(CALLER);member(TARGET);var executor=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
        try(Connection blocker=admin()){
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id=? FOR UPDATE")){
                statement.setLong(1,TARGET);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
            }
            var task=executor.submit(()->{started.countDown();candidate.create(CALLER,TARGET);returned();});
            try{
                assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);boolean waiting=false;
                while(System.nanoTime()<deadline){
                    if(scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='f25-note-block-writer' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0")>0){waiting=true;break;}
                    Thread.sleep(20);
                }
                assertThat(waiting).isTrue();assertThat(contactIds).isEmpty();assertThat(candidateClock.calls).hasValue(0);assertThat(task.isDone()).isFalse();
                blocker.commit();task.get(8,TimeUnit.SECONDS);assertThat(contactIds).containsExactly(CALLER,TARGET);assertThat(candidateClock.calls).hasValue(1);assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);
            }finally{if(!blocker.getAutoCommit())blocker.rollback();if(!task.isDone())task.cancel(true);}
        }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        returned();
    }
    @Test void givenTstzMicrosecondsAndALaterDuplicateClock__whenBothCreateAndSerializeInEitherZone__thenStoreTheSameInstantAndKeepTheFirstTimestampWithoutLocalShift()throws Exception{
        user(CALLER);user(TARGET);original.create(CALLER,TARGET);returned();assertThat(createdAt(CALLER,TARGET)).isEqualTo(FIRST);String expected=snapshot();
        clearOwnedCommunityRows();candidate.create(CALLER,TARGET);returned();assertThat(snapshot()).isEqualTo(expected);
        resetObservations(LATER);original.create(CALLER,TARGET);candidate.create(CALLER,TARGET);returned();assertThat(snapshot()).isEqualTo(expected);
        assertThat(originalClock.calls).hasValue(1);assertThat(candidateClock.calls).hasValue(1);
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            assertThat(context.getBean(ObjectMapper.class).valueToTree(Map.of("createdAt",createdAt(CALLER,TARGET))).path("createdAt").asText()).isEqualTo(FIRST.toString());
        }
    }
    @Test @Order(Integer.MAX_VALUE)
    void givenTheOriginalIssuerAndOwnedKeyFile__whenRefreshingOnlyTheUsedF25PrincipalLast__thenKeepEarlierTokensAndTheExactSixHundredSecondTtlAndMode()throws Exception{
        verifyOwnership();String run=runOwner();Path path=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(path).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));
        assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();assertThat(path.toRealPath()).isEqualTo(path);
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper();Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(path),new TypeReference<Map<String,String>>(){}));var prior=Map.copyOf(tokens);
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");var key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var user=User.builder().userId(9_979_001L).email("f25-token@fixture.invalid").name("F25 synthetic").nickname("F25token").provider("GOOGLE").role(Role.ROLE_USER).build();
        String token=new JwtProvider(secret,600_000,600_000).createAccessToken(user);
        assertThat(new PostTokenAuthenticator(key).authenticate("Bearer "+token)).isEqualTo(9_979_001L);
        var claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();assertThat(claims.getExpiration().getTime()-claims.getIssuedAt().getTime()).isEqualTo(600_000L);
        tokens.put("F25_CALLER",token);for(var entry:prior.entrySet())if(!entry.getKey().equals("F25_CALLER"))assertThat(java.util.Objects.equals(tokens.get(entry.getKey()),entry.getValue())).as("Earlier token values are preserved without rendering credentials").isTrue();
        assertThat(tokens.keySet()).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(prior.keySet().stream(),java.util.stream.Stream.of("F25_CALLER")).collect(java.util.stream.Collectors.toSet()));
        Files.writeString(path,mapper.writeValueAsString(tokens));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));returned();
    }

    private void resetObservations(Instant instant){contactIds.clear();candidateClock.reset(instant);originalClock.reset(instant);if(original!=null)original.resetObservations();}
    private RuntimeException failure(Runnable action){try{action.run();}catch(RuntimeException failure){return failure;}throw new AssertionError("Expected the owned synthetic rejection");}
    private String classification(RuntimeException failure){return failure instanceof DataAccessException?"storage":"generic";}
    private void requireOwnedCommitRejection(Throwable failure){
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());var queue=new java.util.ArrayDeque<Throwable>();queue.add(failure);boolean matched=false;
        while(!queue.isEmpty()&&seen.size()<32){
            Throwable current=queue.removeFirst();if(!seen.add(current))continue;
            if(current instanceof PSQLException postgres&&"23514".equals(postgres.getSQLState())&&postgres.getServerErrorMessage()!=null&&"f25_owned_commit_rejection".equals(postgres.getServerErrorMessage().getConstraint()))matched=true;
            if(current.getCause()!=null)queue.add(current.getCause());if(current instanceof SQLException sql&&sql.getNextException()!=null)queue.add(sql.getNextException());
        }
        assertThat(matched).as("Both managers must fail from the same owned deferred constraint at commit").isTrue();
    }
    private void user(long id)throws SQLException{
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")){
            s.setLong(1,id);s.setString(2,"f25-"+id+"@fixture.invalid");s.setString(3,"F25 synthetic");s.setString(4,"F25nickname"+id);s.setString(5,"GOOGLE");s.setString(6,"ROLE_USER");s.executeUpdate();users.add(id);
        }
    }
    private void pending(long id)throws SQLException{execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES("+id+")");pending.add(id);}
    private void member(long id)throws SQLException{if(!members.contains(id)){execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES("+id+")");members.add(id);}}
    private void block(long first,long second,Instant instant)throws SQLException{
        member(first);member(second);try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES(?,?,?)")){
            s.setLong(1,first);s.setLong(2,second);s.setObject(3,OffsetDateTime.ofInstant(instant,ZoneOffset.UTC));s.executeUpdate();blocks.add(new Block(first,second));
        }
    }
    private long countBlock(long first,long second)throws SQLException{return scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+first+" AND blocked_id="+second);}
    private Instant createdAt(long first,long second)throws SQLException{
        try(Connection c=admin();var s=c.prepareStatement("SELECT created_at FROM pawbridge_community.private_note_blocks WHERE member_id=? AND blocked_id=?")){
            s.setLong(1,first);s.setLong(2,second);try(var rows=s.executeQuery()){assertThat(rows.next()).isTrue();Instant value=rows.getObject(1,OffsetDateTime.class).toInstant();assertThat(rows.next()).isFalse();return value;}
        }
    }
    private void trackCreatedKeys()throws SQLException{
        for(long id:NAMESPACE)if(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)==1)members.add(id);
        String ids=CALLER+","+TARGET+","+THIRD;
        try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery("SELECT member_id,blocked_id FROM pawbridge_community.private_note_blocks WHERE member_id IN ("+ids+") OR blocked_id IN ("+ids+")")){
            while(rows.next()){long first=rows.getLong(1),second=rows.getLong(2);assertThat(NAMESPACE).contains(first,second);blocks.add(new Block(first,second));}
        }
    }
    private void clearOwnedCommunityRows()throws SQLException{
        verifyOwnership();trackCreatedKeys();for(Block pair:blocks)execute("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id="+pair.first()+" AND blocked_id="+pair.second());
        deleteIds("pawbridge_community.private_note_members","member_id",members);blocks.clear();members.clear();
    }
    private void deleteIds(String table,String column,Set<Long> keys)throws SQLException{
        if(!Set.of("pawbridge_community.private_note_members.member_id","pawbridge_user.contact_deletions.user_id","pawbridge_user.users.user_id").contains(table+"."+column))throw new IllegalArgumentException("Only owned key cleanup");
        for(long id:keys)execute("DELETE FROM "+table+" WHERE "+column+"="+id);
    }
    private Connection admin()throws SQLException{return owner.admin();}
    private void verifyOwnership()throws SQLException{
        requireStageReceipts();try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery("SELECT value,owner FROM pawbridge_f17_fixture.marker")){
            assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("pawbridge-f17-ci-only-v1");assertThat(rows.getString(2)).isEqualTo(runOwner());assertThat(F3PostgresqlFixture.required("F17_CI_OWNER")).isEqualTo(runOwner());assertThat(rows.next()).isFalse();
        }
    }
    private String runOwner(){String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");assertThat(run).matches("[0-9]+-[0-9]+");return run;}
    private void requireStageReceipts(){
        Path f24=receiptProperty("f24.fixture.receipt","f24-fixture-write.txt"),f25=receiptProperty("f25.fixture.receipt","f25-fixture-write.txt");assertThat(f25.getParent()).isEqualTo(f24.getParent());String run=runOwner();
        receipt(f24,"owner="+run+"\nsource_sha="+SOURCE+"\nfixture=F23 notes8/mailboxes6 plus member-lock/outgoing-block writer\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\nwriter_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE\nnew_sequence_acl=0\n");
        receipt(f25,"owner="+run+"\nsource_sha="+SOURCE+"\nfixture=F24 member-lock/outgoing-block writer plus block INSERT3\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\nwriter_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at)\nnew_sequence_acl=0\n");
        Path evidence=f25.getParent();
        receipt(evidence.resolve("f20-fixture-reuse.txt"),"owner="+run+"\nfixture=F17/F18/F19 reused\nnew_business_ddl=0\nadded_reader_column=private_note_blocks.created_at\nnew_writer_acl=0\nnew_sequence_acl=0\nnew_marker=0\n");
        receipt(evidence.resolve("f21-fixture-reuse.txt"),f21Proof(run));
        receipt(evidence.resolve("f22-fixture-reuse.txt"),"owner="+run+"\nsource_sha=cba94f898399674d4f708ce0c01e2192a18bc6c7\nfixture=F21 notes/mailboxes and existing F20 blocks\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\nadded_reader_columns=notes4/mailboxes1\nreader_columns=notes8/mailboxes6\nnew_writer_acl=0\nnew_sequence_acl=0\n");
        receipt(evidence.resolve("f23-fixture-reuse.txt"),"owner="+run+"\nsource_sha="+SOURCE+"\nfixture=unchanged F22 notes-eight/mailboxes-six and F20 blocks\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\nadded_reader_columns=0\nreader_columns=notes8/mailboxes6\nnew_writer_acl=0\nnew_sequence_acl=0\n");
    }
    private Path receiptProperty(String property,String name){String raw=System.getProperty(property);if(raw==null)throw new IllegalStateException("Owned stage receipt property is required");Path path=Path.of(raw).toAbsolutePath().normalize();assertThat(path.getFileName().toString()).isEqualTo(name);return path;}
    private void receipt(Path path,String expected){
        assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();
        try{assertThat(path.toRealPath()).isEqualTo(path);assertThat(Files.readString(path)).isEqualTo(expected);}catch(java.io.IOException failure){throw new IllegalStateException("Owned stage receipt cannot be verified");}
    }
    private String f21Proof(String run){
        return "owner="+run+"\nfixture=F20 plus original notes/mailboxes\nnew_business_tables=2\nnew_schema=0\nnew_marker=0\nreader_columns=notes4/mailboxes5\nwriter_acl=0\nsequence_acl=0\nsource_sha=d436e3c43fa220cd4004d9f39040f809915ec6ae\n"
            +"3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119  community-service/src/migration/resources/db/postgresql/V6__private_notes.sql\n"
            +"f1dcd3476bf56eb7862ffcdbdaa0546a59c94a0fb637c04c7a5d6fb677da1cc6  community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql\n"
            +"d2572afc2644df3f932872bcd757e47937cdf7d5b89789439cb103de6c71512d  community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql\n"
            +"ca49dcb049a07c9d7487496cb5a0d7a4e33fe10a3d77255353ca1defe6d5f7f1  original-fragment-0\n"
            +"ec1b4aeea247dde28a2992ba714e40682abcbc7ef2181bbb49432cb22ba66a29  original-fragment-1\n"
            +"abaad1e5d3b3de943761406121fb89fd76746736267f64d2cd4f5dfb5487be2f  original-fragment-2\n"
            +"435db5c5bf80440787d75f6409306e6f8829a8ee63a98284003c1f14aac6c021  original-fragment-3\n"
            +"4419cbf1b5cfc7cc097c21230fa1e616a3496addee756954b8f6b004789b34d4  original-fragment-4\n"
            +"2f5c96cfa237a5f3ac3e5fa647181e175e95342f7ca2db99f9bab02aebfc88b3  original-fragment-5\n";
    }
    private void requireNicknamePrivilege()throws SQLException{assertThat(scalar("SELECT has_column_privilege('pawbridge_f2_reader','pawbridge_user.users','nickname','SELECT')::int")).isOne();}
    private void requireWriterPrivileges()throws SQLException{
        assertThat(scalar("SELECT (has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','UPDATE') AND has_table_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','DELETE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','member_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','blocked_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','member_id','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','blocked_id','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_blocks','created_at','INSERT'))::int")).isOne();
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
    private static final class SampleClock extends Clock {
        private volatile Instant value=FIRST;
        private final AtomicInteger calls=new AtomicInteger();
        void reset(Instant instant){value=instant;calls.set(0);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        @Override public Instant instant(){calls.incrementAndGet();return value;}
    }
    private record Block(long first,long second){}
}
