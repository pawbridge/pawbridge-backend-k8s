package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite;
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteFavoriteMutation;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
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

/** Prepared, unexecuted. Real source JpaTM and candidate JdbcTM; mock source Stream tests hints, not SSE transport. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteFavoriteCompatibilityTest {
    private static final long CALLER=9_981_101L,TARGET=9_981_102L,OTHER=9_981_103L;
    private static final String SOURCE="f06a72a06bd26662c647bde780dfc7a27bf7349c";
    private static final Instant FIRST=Instant.parse("2031-01-01T00:00:00.123456Z"),EXPIRY=FIRST.plusSeconds(3600);
    private static final UUID NOTE=UUID.fromString("00000000-0000-0000-0000-000099811101");
    private static final Set<Long> NAMESPACE=Set.of(CALLER,TARGET,OTHER);
    private final Set<Long> users=new LinkedHashSet<>(),members=new LinkedHashSet<>(),pending=new LinkedHashSet<>();
    private final Set<UUID> notes=new LinkedHashSet<>();
    private final List<Long> contacts=new CopyOnWriteArrayList<>();
    private final List<String> hints=new CopyOnWriteArrayList<>();
    private final Samples sourceClock=new Samples(),candidateClock=new Samples();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader,writer;
    private JdbcTemplate readJdbc;
    private TrackingJdbc writeJdbc;
    private SetPrivateNoteFavorite candidate;
    private F27LegacyNoteFavoriteOracle original;
    private String baseline;
    private boolean namespaceOwned,throwHint;
    private boolean expectedCommittedFavorite;

    @BeforeAll void openOnlyTheOwnedF27StageWithRealOriginalJpaAndTwoSingleConnectionCandidatePools()throws Exception{
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        owner=F3PostgresqlFixture.open();verifyOwnership();requireWriterPrivileges();
        try{
            reader=pool("f27-note-favorite-reader",true,owner.reads().applicationUser(),owner.reads().applicationPassword());
            writer=pool("f27-note-favorite-writer",false,F3PostgresqlFixture.WRITER,owner.writerPassword());
            readJdbc=new JdbcTemplate(reader);readJdbc.setQueryTimeout(3);writeJdbc=new TrackingJdbc(writer);writeJdbc.setQueryTimeout(5);
            var readTM=new JdbcTransactionManager(reader);readTM.setEnforceReadOnly(true);
            var readTx=new TransactionTemplate(readTM);readTx.setReadOnly(true);readTx.setTimeout(3);
            var query=new JdbcContactMemberQuery(readJdbc,readTx);
            ContactMemberQuery membersQuery=id->{
                contacts.add(id);assertThat(id).isEqualTo(CALLER);
                assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
                try{return query.get(id);}finally{
                    assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
                    assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                    assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
                    assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isOne();
                }
            };
            var tx=new TransactionTemplate(new JdbcTransactionManager(writer));tx.setTimeout(15);
            candidate=new SetPrivateNoteFavorite(membersQuery,new JdbcNoteFavoriteMutation(writeJdbc,tx,id->committedHint("candidate",id)),candidateClock);
            original=new F27LegacyNoteFavoriteOracle(owner,reader,sourceClock,id->committedHint("source",id));
            returned(); // EMF bootstrap is not a request observation.
        }catch(Exception|Error failure){closeOnlyOwnedOracleAndPools();throw failure;}
    }
    private HikariDataSource pool(String name,boolean readOnly,String user,String password){
        var c=new HikariConfig();c.setJdbcUrl(owner.reads().jdbcUrl());c.setUsername(user);c.setPassword(password);c.setPoolName(name);
        c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setReadOnly(readOnly);c.setConnectionTimeout(2000);c.setValidationTimeout(1000);
        c.addDataSourceProperty("socketTimeout","20");c.addDataSourceProperty("ApplicationName",name);
        c.setConnectionInitSql("SET statement_timeout='18s'; SET lock_timeout='10s'");return new HikariDataSource(c);
    }
    @BeforeEach void reserveAbsentOwnedKeysAndSnapshotAllSixteenTables()throws Exception{
        namespaceOwned=false;baseline=null;verifyOwnership();requireWriterPrivileges();returned();reset(FIRST);throwHint=false;expectedCommittedFavorite=false;
        for(long id:NAMESPACE){
            for(String pair:List.of("pawbridge_user.users:user_id","pawbridge_user.contact_deletions:user_id","pawbridge_community.private_note_members:member_id")){
                String[] p=pair.split(":");assertThat(scalar("SELECT count(*) FROM "+p[0]+" WHERE "+p[1]+"="+id)).isZero();
            }
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id="+id+" OR recipient_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+id+" OR blocked_id="+id)).isZero();
        }
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id='"+NOTE+"'")).isZero();
        baseline=snapshot();namespaceOwned=true;
    }
    @AfterEach void cleanOnlySuccessfulReservedKeysAndRequireTheFullInitialSnapshot()throws Exception{
        if(owner==null||!namespaceOwned)return;verifyOwnership();returned();trackMembers();
        for(UUID id:notes){
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE reply_to='"+id+"'")).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+id+"' AND member_id NOT IN ("+CALLER+","+TARGET+","+OTHER+")")).isZero();
            execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+id+"'");
            execute("DELETE FROM pawbridge_community.private_notes WHERE note_id='"+id+"'");
        }
        for(long id:members){
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+id+" OR blocked_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id="+id+" OR recipient_id="+id)).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+id)).isZero();
            execute("DELETE FROM pawbridge_community.private_note_members WHERE member_id="+id);
        }
        for(long id:pending)execute("DELETE FROM pawbridge_user.contact_deletions WHERE user_id="+id);
        for(long id:users)execute("DELETE FROM pawbridge_user.users WHERE user_id="+id);
        notes.clear();members.clear();pending.clear();users.clear();assertThat(snapshot()).isEqualTo(baseline);namespaceOwned=false;returned();
    }
    @AfterAll void closeOnlyOwnedOracleAndPools(){try{if(original!=null)original.close();}finally{try{if(reader!=null)reader.close();}finally{if(writer!=null)writer.close();}}}

    @ParameterizedTest @ValueSource(strings={"INBOX","SENT"})
    void givenEitherOwnedDirectionAndRepeatedBooleanValues__whenBothManagersSetFavorite__thenSampleClockTwiceEveryUpdateAndOnlyChangeTheOwnedFavoriteCell(String direction)throws Exception{
        user(CALLER);note(CALLER,direction,EXPIRY);oppositeMailbox(direction); // Counterpart has no User row: no target lookup is permitted.
        boolean previous=false;
        for(boolean value:new boolean[]{true,true,false}){
            String before=snapshot();setFavorite(value);String expected=snapshot();setFavorite(previous);assertThat(snapshot()).isEqualTo(before);
            reset(FIRST);expectedCommittedFavorite=value;original.set(CALLER,NOTE,value);returned();
            assertThat(snapshot()).isEqualTo(expected);assertThat(sourceClock.calls).hasValue(2);assertThat(original.favoriteUpdateCalls()).isOne();assertThat(original.affectedRows()).isOne();assertThat(original.contactIds()).containsExactly(CALLER);assertThat(hints).containsExactly("source");
            setFavorite(previous);reset(FIRST);expectedCommittedFavorite=value;candidate.set(CALLER,NOTE,value);returned();
            assertThat(snapshot()).isEqualTo(expected);assertThat(candidateClock.calls).hasValue(2);assertThat(writeJdbc.attempts).hasValue(1);assertThat(writeJdbc.rows).isOne();assertThat(contacts).containsExactly(CALLER);assertThat(hints).containsExactly("candidate");
            previous=value;
        }
    }
    @ParameterizedTest @ValueSource(strings={"missing-current","pending-current","missing-note","foreign","expired"})
    void givenInactiveCurrentOrAbsentStrictOwnership__whenBothSetFavorite__thenPreserveErrorPriorityAndRollBackWithoutUpdateOrHint(String kind)throws Exception{
        if(!kind.equals("missing-current"))user(CALLER);if(kind.equals("pending-current")){execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES("+CALLER+")");pending.add(CALLER);}
        if(kind.equals("foreign"))note(OTHER,"INBOX",EXPIRY);if(kind.equals("expired"))note(CALLER,"INBOX",FIRST);
        String before=snapshot();int status=kind.endsWith("current")?403:404;
        RuntimeException source=failure(()->original.set(CALLER,NOTE,true));returned();assertThat(source).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)source).getStatusCode().value()).isEqualTo(status);assertThat(snapshot()).isEqualTo(before);
        RuntimeException next=failure(()->candidate.set(CALLER,NOTE,true));returned();assertThat(next).isInstanceOf(NoteFavoriteRejectedException.class);assertThat(((NoteFavoriteRejectedException)next).reason().name()).isEqualTo(status==403?"INACTIVE":"NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);assertThat(hints).isEmpty();assertThat(sourceClock.calls).hasValue(status==403?0:1);assertThat(candidateClock.calls).hasValue(status==403?0:1);assertThat(writeJdbc.attempts).hasValue(0);assertThat(original.favoriteUpdateCalls()).isZero();
    }
    @Test void givenUserNicknamePrivilegeDenied__whenSettingFavorite__thenPreserveTypedMemberFailureAndRestoreBeforeRecovery()throws Exception{
        user(CALLER);note(CALLER,"INBOX",EXPIRY);String before=snapshot();execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try{
            assertThat(failure(()->original.set(CALLER,NOTE,true))).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));returned();assertThat(snapshot()).isEqualTo(before);
            assertThat(failure(()->candidate.set(CALLER,NOTE,true))).isInstanceOf(ContactMemberUnavailableException.class);returned();assertThat(snapshot()).isEqualTo(before);assertThat(hints).isEmpty();assertThat(candidateClock.calls).hasValue(0);assertThat(sourceClock.calls).hasValue(0);
        }finally{verifyOwnership();execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");requireNicknamePrivilege();requireWriterPrivileges();}
        reset(FIRST);expectedCommittedFavorite=true;candidate.set(CALLER,NOTE,true);returned();assertThat(favorite()).isTrue();assertThat(hints).containsExactly("candidate");
    }
    @Test void givenOnlyFavoriteUpdatePrivilegeDenied__whenSettingFavorite__thenRestrictedWriterFailsAfterTwoClocksAndRecoversWithoutBroadGrants()throws Exception{
        user(CALLER);note(CALLER,"INBOX",EXPIRY);String before=snapshot();execute("REVOKE UPDATE(favorite) ON pawbridge_community.private_note_mailboxes FROM pawbridge_f3_writer");
        try{
            // The original reference owns its admin connection: this denial intentionally targets only the restricted candidate.
            assertThat(failure(()->candidate.set(CALLER,NOTE,true))).isInstanceOf(DataAccessException.class);returned();assertThat(snapshot()).isEqualTo(before);assertThat(hints).isEmpty();assertThat(candidateClock.calls).hasValue(2);assertThat(writeJdbc.attempts).hasValue(1);
        }finally{verifyOwnership();execute("GRANT UPDATE(favorite) ON pawbridge_community.private_note_mailboxes TO pawbridge_f3_writer");requireWriterPrivileges();requireNicknamePrivilege();}
        reset(FIRST);expectedCommittedFavorite=true;candidate.set(CALLER,NOTE,true);returned();assertThat(favorite()).isTrue();
    }
    @Test void givenOwnedDeferredFavoriteCommitRejection__whenBothManagersCommit__thenMatchActualConstraintCauseAndKeepRowsAndHintsUnchanged()throws Exception{
        user(CALLER);note(CALLER,"INBOX",EXPIRY);String before=snapshot();String name="f27_commit_"+F3PostgresqlFixture.required("GITHUB_RUN_ID")+"_"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");assertThat(name).matches("[a-z0-9_]{1,60}");
        assertThat(scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='pawbridge_f17_fixture' AND p.proname='"+name+"'")).isZero();assertThat(scalar("SELECT count(*) FROM pg_trigger WHERE tgname='"+name+"'")).isZero();boolean function=false,trigger=false;
        try{
            execute("CREATE FUNCTION pawbridge_f17_fixture."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.member_id="+CALLER+" AND NEW.note_id='"+NOTE+"'::uuid THEN RAISE EXCEPTION USING ERRCODE='23514', CONSTRAINT='f27_owned_commit_rejection', MESSAGE='synthetic commit rejection'; END IF; RETURN NULL; END; $$");function=true;
            execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER UPDATE ON pawbridge_community.private_note_mailboxes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f17_fixture."+name+"()");trigger=true;
            RuntimeException a=failure(()->original.set(CALLER,NOTE,true));returned();assertThat(snapshot()).isEqualTo(before);RuntimeException b=failure(()->candidate.set(CALLER,NOTE,true));returned();assertThat(snapshot()).isEqualTo(before);
            requireOwnedCommitRejection(a);requireOwnedCommitRejection(b);assertThat(a instanceof DataAccessException).isEqualTo(b instanceof DataAccessException);assertThat(hints).isEmpty();assertThat(sourceClock.calls).hasValue(2);assertThat(candidateClock.calls).hasValue(2);
        }finally{verifyOwnership();try{if(trigger)execute("DROP TRIGGER "+name+" ON pawbridge_community.private_note_mailboxes");}finally{if(function)execute("DROP FUNCTION pawbridge_f17_fixture."+name+"()");}}
    }
    @ParameterizedTest @ValueSource(strings={"present","deleted","expired"})
    void givenHeldNoteLockAndPossibleOwnershipLoss__whenBothSetFavorite__thenActuallyWaitAndRecheckBeforeTheBooleanUpdateOrHint(String kind)throws Exception{
        user(CALLER);
        for(boolean sourceSide:new boolean[]{true,false}){
            if(notes.contains(NOTE)){execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+NOTE+"'");execute("DELETE FROM pawbridge_community.private_notes WHERE note_id='"+NOTE+"'");}
            note(CALLER,"INBOX",EXPIRY);reset(FIRST);expectedCommittedFavorite=true;var executor=Executors.newSingleThreadExecutor();
            try(Connection blocker=admin()){
                blocker.setAutoCommit(false);try(var statement=blocker.prepareStatement("SELECT note_id FROM pawbridge_community.private_notes WHERE note_id=? FOR UPDATE")){statement.setObject(1,NOTE);try(var values=statement.executeQuery()){assertThat(values.next()).isTrue();}}
                var task=executor.submit(()->{try{if(sourceSide)original.set(CALLER,NOTE,true);else candidate.set(CALLER,NOTE,true);return (RuntimeException)null;}catch(RuntimeException failure){return failure;}finally{returned();}});
                try{
                    String application=sourceSide?"f27-original-note-favorite":"f27-note-favorite-writer";long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);boolean waiting=false;
                    while(System.nanoTime()<deadline){if(scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+application+"' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0")>0){waiting=true;break;}Thread.sleep(20);}
                    assertThat(waiting).isTrue();assertThat(sourceSide?original.contactIds():contacts).containsExactly(CALLER);assertThat(sourceSide?sourceClock.calls:candidateClock.calls).hasValue(1);assertThat(hints).isEmpty();assertThat(task.isDone()).isFalse();
                    if(kind.equals("deleted")){
                        try(var statement=blocker.prepareStatement("DELETE FROM pawbridge_community.private_note_mailboxes WHERE note_id=?")){statement.setObject(1,NOTE);statement.executeUpdate();}
                        try(var statement=blocker.prepareStatement("DELETE FROM pawbridge_community.private_notes WHERE note_id=?")){statement.setObject(1,NOTE);statement.executeUpdate();}
                    }
                    if(kind.equals("expired")){try(var statement=blocker.prepareStatement("UPDATE pawbridge_community.private_notes SET expires_at=? WHERE note_id=?")){statement.setObject(1,OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC));statement.setObject(2,NOTE);statement.executeUpdate();}}
                    blocker.commit();RuntimeException outcome=task.get(8,TimeUnit.SECONDS);returned();
                    if(kind.equals("present")){assertThat(outcome).isNull();assertThat(sourceSide?sourceClock.calls:candidateClock.calls).hasValue(2);assertThat(favorite()).isTrue();assertThat(hints).containsExactly(sourceSide?"source":"candidate");}
                    else{
                        if(sourceSide){assertThat(outcome).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)outcome).getStatusCode().value()).isEqualTo(404);}
                        else{assertThat(outcome).isInstanceOf(NoteFavoriteRejectedException.class);assertThat(((NoteFavoriteRejectedException)outcome).reason().name()).isEqualTo("NOT_FOUND");}
                        assertThat(sourceSide?sourceClock.calls:candidateClock.calls).hasValue(2);assertThat(sourceSide?original.favoriteUpdateCalls():writeJdbc.attempts.get()).isZero();assertThat(hints).isEmpty();
                    }
                }finally{blocker.rollback();}
            }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        }
    }
    @Test void givenHintRuntimeAfterCommit__whenBothSetFavorite__thenObserveCommittedBooleanOnIndependentConnectionAndKeepWriteSuccess()throws Exception{
        user(CALLER);note(CALLER,"INBOX",EXPIRY);throwHint=true;expectedCommittedFavorite=true;original.set(CALLER,NOTE,true);returned();String expected=snapshot();assertThat(hints).containsExactly("source");setFavorite(false);reset(FIRST);candidate.set(CALLER,NOTE,true);returned();assertThat(snapshot()).isEqualTo(expected);assertThat(hints).containsExactly("candidate");
    }
    @Test @Order(Integer.MAX_VALUE)
    void givenOwnedIssuerAndPriorCredentials__whenRefreshingOnlyF27Last__thenKeepTwoNormalSixHundredSecondTokensAndAllEarlierKeys()throws Exception{
        verifyOwnership();String run=runOwner();Path path=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(path).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();assertThat(path.toRealPath()).isEqualTo(path);assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper();Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(path),new TypeReference<Map<String,String>>(){}));var prior=Map.copyOf(tokens);
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");var key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));var issuer=new JwtProvider(secret,600_000,600_000);
        var ids=new LinkedHashMap<String,Long>();ids.put("F27_CALLER",9_981_001L);ids.put("F27_OTHER",9_981_002L);
        for(var entry:ids.entrySet()){
            long id=entry.getValue();var user=User.builder().userId(id).email("f27-token-"+id+"@fixture.invalid").name("F27 synthetic").nickname("F27token"+id).provider("GOOGLE").role(Role.ROLE_USER).build();String token=issuer.createAccessToken(user);
            assertThat(new PostTokenAuthenticator(key).authenticate("Bearer "+token)).isEqualTo(id);var claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();assertThat(claims.getExpiration().getTime()-claims.getIssuedAt().getTime()).isEqualTo(600_000L);tokens.put(entry.getKey(),token);
        }
        Set<String> used=ids.keySet();for(var e:prior.entrySet())if(!used.contains(e.getKey()))assertThat(Objects.equals(tokens.get(e.getKey()),e.getValue())).as("Earlier credentials remain unchanged without rendering them").isTrue();
        assertThat(tokens.keySet()).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(prior.keySet().stream(),used.stream()).collect(java.util.stream.Collectors.toSet()));Files.writeString(path,mapper.writeValueAsString(tokens));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));returned();
    }
    private void committedHint(String side,long id){
        assertThat(id).isEqualTo(CALLER);
        try{assertThat(favorite()).as("A new independent admin connection sees the committed own favorite value").isEqualTo(expectedCommittedFavorite);}catch(SQLException failure){throw new IllegalStateException("Synthetic commit visibility lookup failed",failure);}
        hints.add(side); // Spring may still report active resources inside afterCommit; do not require false here.
        if(throwHint)throw new IllegalStateException("synthetic postcommit hint failure");
    }
    private void reset(Instant now){contacts.clear();hints.clear();sourceClock.sequence(now);candidateClock.sequence(now);if(original!=null)original.resetObservations();if(writeJdbc!=null){writeJdbc.attempts.set(0);writeJdbc.rows=-1;}}
    private RuntimeException failure(Runnable action){try{action.run();}catch(RuntimeException e){return e;}throw new AssertionError("Expected owned synthetic failure");}
    private void requireOwnedCommitRejection(Throwable failure){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var queue=new ArrayDeque<Throwable>();queue.add(failure);boolean matched=false;
        while(!queue.isEmpty()&&seen.size()<32){Throwable next=queue.removeFirst();if(!seen.add(next))continue;if(next instanceof PSQLException p&&"23514".equals(p.getSQLState())&&p.getServerErrorMessage()!=null&&"f27_owned_commit_rejection".equals(p.getServerErrorMessage().getConstraint()))matched=true;if(next.getCause()!=null)queue.add(next.getCause());if(next instanceof SQLException s&&s.getNextException()!=null)queue.add(s.getNextException());}
        assertThat(matched).as("Both managers fail from the same owned deferred constraint").isTrue();
    }
    private void user(long id)throws SQLException{
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")){s.setLong(1,id);s.setString(2,"f27-"+id+"@fixture.invalid");s.setString(3,"F27 synthetic");s.setString(4,"F27nickname"+id);s.setString(5,"GOOGLE");s.setString(6,"ROLE_USER");s.executeUpdate();users.add(id);}
    }
    private void member(long id)throws SQLException{if(!members.contains(id)){execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES("+id+")");members.add(id);}}
    private void note(long mailbox,String direction,Instant expiry)throws SQLException{
        member(CALLER);member(TARGET);if(mailbox==OTHER)member(OTHER);
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES(?,?,?,?,?,?)")){s.setObject(1,NOTE);s.setLong(2,direction.equals("SENT")?CALLER:TARGET);s.setLong(3,direction.equals("SENT")?TARGET:CALLER);s.setString(4,"F27 private fixture body");s.setObject(5,OffsetDateTime.ofInstant(FIRST.minusSeconds(120),ZoneOffset.UTC));s.setObject(6,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC));s.executeUpdate();notes.add(NOTE);}
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,created_at) VALUES(?,?,?,?,?)")){s.setLong(1,mailbox);s.setObject(2,NOTE);s.setString(3,direction);s.setObject(4,direction.equals("SENT")?OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC):null);s.setObject(5,OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC));s.executeUpdate();}
    }
    private void oppositeMailbox(String currentDirection)throws SQLException{
        try(Connection c=admin();var statement=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES(?,?,?,?,?,?)")){
            statement.setLong(1,TARGET);statement.setObject(2,NOTE);statement.setString(3,currentDirection.equals("INBOX")?"SENT":"INBOX");statement.setObject(4,OffsetDateTime.ofInstant(FIRST.minusSeconds(30),ZoneOffset.UTC));statement.setBoolean(5,true);statement.setObject(6,OffsetDateTime.ofInstant(FIRST.minusSeconds(120),ZoneOffset.UTC));statement.executeUpdate();
        }
    }
    private void setFavorite(boolean value)throws SQLException{try(Connection c=admin();var statement=c.prepareStatement("UPDATE pawbridge_community.private_note_mailboxes SET favorite=? WHERE member_id=? AND note_id=?")){statement.setBoolean(1,value);statement.setLong(2,CALLER);statement.setObject(3,NOTE);assertThat(statement.executeUpdate()).isOne();}}
    private boolean favorite()throws SQLException{try(Connection c=admin();var statement=c.prepareStatement("SELECT favorite FROM pawbridge_community.private_note_mailboxes WHERE member_id=? AND note_id=?")){statement.setLong(1,CALLER);statement.setObject(2,NOTE);try(var values=statement.executeQuery()){assertThat(values.next()).isTrue();boolean value=values.getBoolean(1);assertThat(values.wasNull()).isFalse();assertThat(values.next()).isFalse();return value;}}}
    private void trackMembers()throws SQLException{for(long id:NAMESPACE)if(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)==1)members.add(id);}
    private static final class Samples extends Clock{
        private volatile Instant[] values={FIRST};private final AtomicInteger calls=new AtomicInteger();
        void sequence(Instant... values){this.values=values.clone();calls.set(0);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return Clock.fixed(values[0],zone);}@Override public Instant instant(){int n=calls.getAndIncrement();return values[Math.min(n,values.length-1)];}
    }
    private static final class TrackingJdbc extends JdbcTemplate{
        private final AtomicInteger attempts=new AtomicInteger();private int rows=-1;
        TrackingJdbc(javax.sql.DataSource dataSource){super(dataSource);}
        @Override public int update(String sql,Object... args){boolean favorite=sql.replaceAll("\\s+"," ").strip().startsWith("UPDATE pawbridge_community.private_note_mailboxes SET favorite");if(favorite)attempts.incrementAndGet();int count=super.update(sql,args);if(favorite)rows=count;return count;}
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
        Path f26=receiptProperty("f26.fixture.receipt","f26-fixture-write.txt");assertThat(f26.getParent()).isEqualTo(f25.getParent());
        receipt(f26,"owner="+run+"\nsource_sha="+SOURCE+"\nfixture=F25 plus own-note read writer\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\nwriter_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);notes:SELECT(note_id,expires_at),UPDATE(note_id);mailboxes:SELECT(member_id,note_id,direction,read_at),UPDATE(read_at)\nnew_sequence_acl=0\n");
        Path f27=receiptProperty("f27.fixture.receipt","f27-fixture-write.txt");assertThat(f27.getParent()).isEqualTo(f26.getParent());
        receipt(f27,"owner="+run+"\nsource_sha="+SOURCE+"\nfixture=F26 plus own-note favorite writer\nnew_business_ddl=0\nnew_schema=0\nnew_marker=0\nwriter_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);notes:SELECT(note_id,expires_at),UPDATE(note_id);mailboxes:SELECT(member_id,note_id,direction,read_at),UPDATE(read_at,favorite)\nnew_sequence_acl=0\n");
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
        assertThat(scalar("SELECT (has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','UPDATE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','note_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','expires_at','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','note_id','UPDATE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','member_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','note_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','direction','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','read_at','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','read_at','UPDATE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','favorite','UPDATE') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','favorite','SELECT') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','body','SELECT') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','expires_at','UPDATE'))::int")).isOne();
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
}
