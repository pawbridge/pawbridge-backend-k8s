package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote;
import com.pawbridge.backend.community.internal.note.application.NoteDeletionRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteDeletion;
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
import java.nio.file.*;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Prepared/unexecuted: actual source JpaTM and candidate JdbcTM; mock original Stream tests committed hints, not SSE. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteDeletionCompatibilityTest {
    private static final long CALLER=9_982_101L,TARGET=9_982_102L,OTHER=9_982_103L;
    private static final String SOURCE="f06a72a06bd26662c647bde780dfc7a27bf7349c";
    private static final Instant FIRST=Instant.parse("2031-01-01T00:00:00.123456Z"),EXPIRY=FIRST.plusSeconds(3600);
    private static final UUID NOTE=UUID.fromString("00000000-0000-0000-0000-000099821101"),CHILD=UUID.fromString("00000000-0000-0000-0000-000099821102"),REQUEST=UUID.fromString("00000000-0000-0000-0000-000099821103");
    private static final Set<Long>NAMESPACE=Set.of(CALLER,TARGET,OTHER);
    private final Set<Long>users=new LinkedHashSet<>(),members=new LinkedHashSet<>(),pending=new LinkedHashSet<>();
    private final Set<UUID>notes=new LinkedHashSet<>();
    private final List<Long>contacts=new CopyOnWriteArrayList<>();
    private final List<String>hints=new CopyOnWriteArrayList<>();
    private final Samples sourceClock=new Samples(),candidateClock=new Samples();
    private F3PostgresqlFixture owner;private HikariDataSource reader,writer;private TrackingJdbc writeJdbc;
    private DeletePrivateNote candidate;private F28LegacyNoteDeletionOracle original;
    private String baseline;private boolean namespaceOwned,requestOwned,throwHint;

    @BeforeAll void openOnlyOwnedF28WithActualSourceJpaAndTwoSingleConnectionCandidatePools()throws Exception{
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");owner=F3PostgresqlFixture.open();verifyOwnership();requireWriterPrivileges();
        try{
            reader=pool("f28-note-deletion-reader",true,owner.reads().applicationUser(),owner.reads().applicationPassword());writer=pool("f28-note-deletion-writer",false,F3PostgresqlFixture.WRITER,owner.writerPassword());
            var readJdbc=new JdbcTemplate(reader);readJdbc.setQueryTimeout(3);writeJdbc=new TrackingJdbc(writer);writeJdbc.setQueryTimeout(5);
            var readTM=new JdbcTransactionManager(reader);readTM.setEnforceReadOnly(true);var readTx=new TransactionTemplate(readTM);readTx.setReadOnly(true);readTx.setTimeout(3);
            var query=new JdbcContactMemberQuery(readJdbc,readTx);
            ContactMemberQuery contact=id->{contacts.add(id);assertThat(id).isEqualTo(CALLER);assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();
                try{return query.get(id);}finally{assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isOne();}};
            var tx=new TransactionTemplate(new JdbcTransactionManager(writer));tx.setTimeout(15);candidate=new DeletePrivateNote(contact,new JdbcNoteDeletion(writeJdbc,tx,id->committedHint("candidate",id)),candidateClock);
            original=new F28LegacyNoteDeletionOracle(owner,reader,sourceClock,id->committedHint("source",id));returned();
        }catch(Exception|Error failure){closeOnlyOwnedOracleAndPools();throw failure;}
    }
    private HikariDataSource pool(String name,boolean readOnly,String user,String password){
        var c=new HikariConfig();c.setJdbcUrl(owner.reads().jdbcUrl());c.setUsername(user);c.setPassword(password);c.setPoolName(name);c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setReadOnly(readOnly);c.setConnectionTimeout(2000);c.setValidationTimeout(1000);c.addDataSourceProperty("socketTimeout","20");c.addDataSourceProperty("ApplicationName",name);c.setConnectionInitSql("SET statement_timeout='18s'; SET lock_timeout='10s'");return new HikariDataSource(c);
    }
    @BeforeEach void reserveAbsentKeysBeforeAllowingCleanupAndSnapshotAllSeventeenTables()throws Exception{
        namespaceOwned=false;baseline=null;verifyOwnership();requireWriterPrivileges();returned();reset(FIRST);throwHint=false;
        for(long id:NAMESPACE){
            for(String pair:List.of("pawbridge_user.users:user_id","pawbridge_user.contact_deletions:user_id","pawbridge_community.private_note_members:member_id")){String[]parts=pair.split(":");assertThat(scalar("SELECT count(*) FROM "+parts[0]+" WHERE "+parts[1]+"="+id)).isZero();}
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id="+id+" OR recipient_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+id+" OR blocked_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id="+id)).isZero();
        }
        for(UUID id:List.of(NOTE,CHILD)){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id='"+id+"'")).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+id+"'")).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE note_id='"+id+"'")).isZero();}
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id='"+REQUEST+"'")).isZero();baseline=snapshot();namespaceOwned=true;
    }
    @AfterEach void removeOnlyAcknowledgedReservedKeysAndRestoreTheFullInitialSnapshot()throws Exception{if(owner!=null&&namespaceOwned){cleanTrackedRows();namespaceOwned=false;}}
    @AfterAll void closeOnlyOwnedOracleAndPools(){try{if(original!=null)original.close();}finally{try{if(reader!=null)reader.close();}finally{if(writer!=null)writer.close();}}}
    private void cleanTrackedRows()throws Exception{
        verifyOwnership();returned();trackMembers();
        for(UUID id:notes){
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE reply_to='"+id+"' AND note_id NOT IN ('"+NOTE+"','"+CHILD+"')")).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE note_id='"+id+"' AND NOT(sender_id="+TARGET+" AND request_id='"+REQUEST+"')")).isZero();
            assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+id+"' AND member_id NOT IN ("+CALLER+","+TARGET+","+OTHER+")")).isZero();
        }
        if(requestOwned)execute("DELETE FROM pawbridge_community.private_note_requests WHERE sender_id="+TARGET+" AND request_id='"+REQUEST+"'");
        for(UUID id:List.of(CHILD,NOTE))if(notes.contains(id)){execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+id+"'");execute("DELETE FROM pawbridge_community.private_notes WHERE note_id='"+id+"'");}
        for(long id:members){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+id+" OR blocked_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id="+id+" OR recipient_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+id)).isZero();execute("DELETE FROM pawbridge_community.private_note_members WHERE member_id="+id);}
        for(long id:pending)execute("DELETE FROM pawbridge_user.contact_deletions WHERE user_id="+id);for(long id:users)execute("DELETE FROM pawbridge_user.users WHERE user_id="+id);
        notes.clear();members.clear();pending.clear();users.clear();requestOwned=false;assertSameSnapshot(baseline);returned();
    }

    @ParameterizedTest @ValueSource(strings={"INBOX-shared","INBOX-last","SENT-last"})
    void givenOwnedInboxOrSentAndRemainingOrLastMailbox__whenBothManagersDeleteAndRepeat__thenOnlyRemoveOwnedMailboxOrOrphanAndItsExactForeignKeyReferences(String scenario)throws Exception{
        String reference=null;
        for(boolean source:new boolean[]{true,false}){
            seedScenario(scenario);String before=snapshot();JsonNode expected=expectedDeletion(before,scenario.endsWith("shared"));reset(FIRST);
            remove(source);returned();assertSnapshot(expected);if(source)reference=snapshot();else assertSameSnapshot(reference);
            assertThat(source?original.deleteAttempts():writeJdbc.attempts.get()).isEqualTo(2);assertThat(source?original.affectedRows():writeJdbc.rows).containsExactly(1,scenario.endsWith("shared")?0:1);assertThat(source?original.contactIds():contacts).containsExactly(CALLER);assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(2);assertThat(hints).containsExactly(source?"source":"candidate");
            String committed=snapshot();reset(FIRST);requireNotFound(failure(()->remove(source)),source);returned();assertSameSnapshot(committed);assertThat(hints).isEmpty();assertThat(source?original.deleteAttempts():writeJdbc.attempts.get()).isZero();assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(1);cleanTrackedRows();
        }
    }
    @ParameterizedTest @ValueSource(strings={"missing-current","pending-current","missing-note","foreign","expired"})
    void givenInactiveCurrentOrAbsentUnexpiredOwnership__whenBothDelete__thenPreservePolicyPriorityAndRollBackWithoutAnyDeleteOrHint(String kind)throws Exception{
        if(!kind.equals("missing-current"))user(CALLER);if(kind.equals("pending-current")){execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES("+CALLER+")");pending.add(CALLER);}
        if(kind.equals("foreign"))note(OTHER,"INBOX",EXPIRY);if(kind.equals("expired"))note(CALLER,"INBOX",FIRST);
        String before=snapshot();int status=kind.endsWith("current")?403:404;
        var a=failure(()->original.delete(CALLER,NOTE));returned();assertThat(a).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)a).getStatusCode().value()).isEqualTo(status);assertSameSnapshot(before);
        var b=failure(()->candidate.delete(CALLER,NOTE));returned();assertThat(b).isInstanceOf(NoteDeletionRejectedException.class);assertThat(((NoteDeletionRejectedException)b).reason().name()).isEqualTo(status==403?"INACTIVE":"NOT_FOUND");assertSameSnapshot(before);assertThat(hints).isEmpty();assertThat(sourceClock.calls).hasValue(status==403?0:1);assertThat(candidateClock.calls).hasValue(status==403?0:1);assertThat(original.deleteAttempts()).isZero();assertThat(writeJdbc.attempts).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={"mailbox","note"})
    void givenEitherDeletePrivilegeDenied__whenCandidateDeletes__thenRollBackBothStagesAndAllForeignKeyChangesRestoreOnlyThatGrantAndRecover(String table)throws Exception{
        seedScenario("INBOX-last");String before=snapshot();String relation=table.equals("mailbox")?"pawbridge_community.private_note_mailboxes":"pawbridge_community.private_notes";execute("REVOKE DELETE ON "+relation+" FROM pawbridge_f3_writer");
        try{assertThat(failure(()->candidate.delete(CALLER,NOTE))).isInstanceOf(DataAccessException.class);returned();assertSameSnapshot(before);assertThat(hints).isEmpty();assertThat(candidateClock.calls).hasValue(2);assertThat(writeJdbc.attempts).hasValue(table.equals("mailbox")?1:2);}
        finally{verifyOwnership();execute("GRANT DELETE ON "+relation+" TO pawbridge_f3_writer");requireWriterPrivileges();requireNicknamePrivilege();}
        reset(FIRST);JsonNode expected=expectedDeletion(before,false);candidate.delete(CALLER,NOTE);returned();assertSnapshot(expected);assertThat(hints).containsExactly("candidate");
    }
    @Test void givenNicknameSelectDenied__whenBothDelete__thenPreserveMemberFailureBeforeTimeAndRestoreBeforeRecovery()throws Exception{
        seedScenario("INBOX-last");String before=snapshot();execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try{var a=failure(()->original.delete(CALLER,NOTE));returned();assertThat(a).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)a).getStatusCode().value()).isEqualTo(503);assertSameSnapshot(before);assertThat(failure(()->candidate.delete(CALLER,NOTE))).isInstanceOf(ContactMemberUnavailableException.class);returned();assertSameSnapshot(before);assertThat(hints).isEmpty();assertThat(sourceClock.calls).hasValue(0);assertThat(candidateClock.calls).hasValue(0);}
        finally{verifyOwnership();execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");requireNicknamePrivilege();requireWriterPrivileges();}
        reset(FIRST);candidate.delete(CALLER,NOTE);returned();assertSnapshot(expectedDeletion(before,false));assertThat(hints).containsExactly("candidate");
    }
    @Test void givenOwnedDeferredDeletionCommitRejection__whenBothManagersCommit__thenRequireTheSameConstraintCauseAndRollbackBothDeletesAndSetNullsWithoutHints()throws Exception{
        seedScenario("INBOX-last");String before=snapshot();String name="f28_commit_"+F3PostgresqlFixture.required("GITHUB_RUN_ID")+"_"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");assertThat(name).matches("[a-z0-9_]{1,60}");assertThat(scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='pawbridge_f17_fixture' AND p.proname='"+name+"'")).isZero();assertThat(scalar("SELECT count(*) FROM pg_trigger WHERE tgname='"+name+"'")).isZero();boolean function=false,trigger=false;
        try{
            execute("CREATE FUNCTION pawbridge_f17_fixture."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.member_id="+CALLER+" AND OLD.note_id='"+NOTE+"'::uuid THEN RAISE EXCEPTION USING ERRCODE='23514', CONSTRAINT='f28_owned_commit_rejection', MESSAGE='synthetic commit rejection'; END IF; RETURN NULL; END; $$");function=true;
            execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER DELETE ON pawbridge_community.private_note_mailboxes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f17_fixture."+name+"()");trigger=true;
            var a=failure(()->original.delete(CALLER,NOTE));returned();assertSameSnapshot(before);var b=failure(()->candidate.delete(CALLER,NOTE));returned();assertSameSnapshot(before);requireOwnedCommitRejection(a);requireOwnedCommitRejection(b);assertThat(a instanceof DataAccessException).isEqualTo(b instanceof DataAccessException);assertThat(hints).isEmpty();assertThat(original.deleteAttempts()).isEqualTo(2);assertThat(writeJdbc.attempts).hasValue(2);
        }finally{verifyOwnership();try{if(trigger)execute("DROP TRIGGER "+name+" ON pawbridge_community.private_note_mailboxes");}finally{if(function)execute("DROP FUNCTION pawbridge_f17_fixture."+name+"()");}}
    }
    @ParameterizedTest @ValueSource(strings={"present","deleted","expired"})
    void givenHeldNoteLockAndPossibleConcurrentOwnershipLoss__whenBothDelete__thenActuallyWaitAndRecheckBeforeEitherDeleteOrHint(String kind)throws Exception{
        for(boolean source:new boolean[]{true,false}){
            seedScenario("INBOX-last");reset(FIRST);var worker=Executors.newSingleThreadExecutor();
            try(Connection blocker=admin()){
                blocker.setAutoCommit(false);try(var statement=blocker.prepareStatement("SELECT note_id FROM pawbridge_community.private_notes WHERE note_id=? FOR UPDATE")){statement.setObject(1,NOTE);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}}
                var task=worker.submit(()->{try{remove(source);return (RuntimeException)null;}catch(RuntimeException failure){return failure;}finally{returned();}});
                try{
                    String application=source?"f28-original-note-deletion":"f28-note-deletion-writer";long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);boolean waiting=false;
                    while(System.nanoTime()<end){if(scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+application+"' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0")>0){waiting=true;break;}Thread.sleep(20);}
                    assertThat(waiting).isTrue();assertThat(task.isDone()).isFalse();assertThat(source?original.contactIds():contacts).containsExactly(CALLER);assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(1);assertThat(hints).isEmpty();
                    if(kind.equals("deleted")){try(var statement=blocker.createStatement()){statement.executeUpdate("DELETE FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+NOTE+"'");statement.executeUpdate("DELETE FROM pawbridge_community.private_notes WHERE note_id='"+NOTE+"'");}}
                    if(kind.equals("expired")){try(var statement=blocker.prepareStatement("UPDATE pawbridge_community.private_notes SET expires_at=? WHERE note_id=?")){statement.setObject(1,OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC));statement.setObject(2,NOTE);statement.executeUpdate();}}
                    blocker.commit();RuntimeException outcome=task.get(8,TimeUnit.SECONDS);returned();assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(2);
                    if(kind.equals("present")){assertThat(outcome).isNull();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+CALLER+" AND note_id='"+NOTE+"'")).isZero();assertThat(hints).containsExactly(source?"source":"candidate");}
                    else{requireNotFound(outcome,source);assertThat(source?original.deleteAttempts():writeJdbc.attempts.get()).isZero();assertThat(hints).isEmpty();}
                }finally{blocker.rollback();}
            }finally{worker.shutdownNow();assertThat(worker.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
            cleanTrackedRows();
        }
    }
    @Test void givenHintRuntimeAfterRealCommit__whenBothDelete__thenObserveMailboxAbsenceOnAnIndependentConnectionAndKeepSuccess()throws Exception{
        for(boolean source:new boolean[]{true,false}){seedScenario("INBOX-last");String before=snapshot();throwHint=true;reset(FIRST);remove(source);returned();assertSnapshot(expectedDeletion(before,false));assertThat(hints).containsExactly(source?"source":"candidate");cleanTrackedRows();}throwHint=false;
    }
    @Test @Order(Integer.MAX_VALUE)
    void givenOwnedIssuerAndPriorCredentials__whenRefreshingOnlyF28Last__thenKeepTwoNormalSixHundredSecondTokensAndAllEarlierKeys()throws Exception{
        verifyOwnership();String run=runOwner();Path path=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();assertThat(path).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();assertThat(path.toRealPath()).isEqualTo(path);assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper();Map<String,String>tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(path),new TypeReference<Map<String,String>>(){}));var prior=Map.copyOf(tokens);String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");var key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));var issuer=new JwtProvider(secret,600_000,600_000);var ids=new LinkedHashMap<String,Long>();ids.put("F28_CALLER",9_982_001L);ids.put("F28_OTHER",9_982_002L);
        for(var entry:ids.entrySet()){long id=entry.getValue();var user=User.builder().userId(id).email("f28-token-"+id+"@fixture.invalid").name("F28 synthetic").nickname("F28token"+id).provider("GOOGLE").role(Role.ROLE_USER).build();String token=issuer.createAccessToken(user);assertThat(new PostTokenAuthenticator(key).authenticate("Bearer "+token)).isEqualTo(id);var claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();assertThat(claims.getExpiration().getTime()-claims.getIssuedAt().getTime()).isEqualTo(600_000L);tokens.put(entry.getKey(),token);}
        for(var e:prior.entrySet())if(!ids.containsKey(e.getKey()))assertThat(Objects.equals(tokens.get(e.getKey()),e.getValue())).as("Earlier credentials stay unchanged without rendering them").isTrue();assertThat(tokens.keySet()).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(prior.keySet().stream(),ids.keySet().stream()).collect(java.util.stream.Collectors.toSet()));Files.writeString(path,mapper.writeValueAsString(tokens));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));returned();
    }

    private void remove(boolean source){if(source)original.delete(CALLER,NOTE);else candidate.delete(CALLER,NOTE);}
    private void committedHint(String side,long id){assertThat(id).isEqualTo(CALLER);try{assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+CALLER+" AND note_id='"+NOTE+"'")).as("Independent connection sees the committed mailbox absence").isZero();}catch(SQLException failure){throw new IllegalStateException("Synthetic commit visibility lookup failed",failure);}hints.add(side);if(throwHint)throw new IllegalStateException("synthetic postcommit hint failure");}
    private void reset(Instant now){contacts.clear();hints.clear();sourceClock.sequence(now);candidateClock.sequence(now);if(original!=null)original.resetObservations();if(writeJdbc!=null){writeJdbc.attempts.set(0);writeJdbc.rows.clear();}}
    private RuntimeException failure(Runnable action){try{action.run();}catch(RuntimeException failure){return failure;}throw new AssertionError("Expected owned synthetic failure");}
    private void requireNotFound(RuntimeException failure,boolean source){if(source){assertThat(failure).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(404);}else{assertThat(failure).isInstanceOf(NoteDeletionRejectedException.class);assertThat(((NoteDeletionRejectedException)failure).reason().name()).isEqualTo("NOT_FOUND");}}
    private void requireOwnedCommitRejection(Throwable failure){var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var queue=new ArrayDeque<Throwable>();queue.add(failure);boolean matched=false;while(!queue.isEmpty()&&seen.size()<32){Throwable next=queue.removeFirst();if(!seen.add(next))continue;if(next instanceof PSQLException e&&"23514".equals(e.getSQLState())&&e.getServerErrorMessage()!=null&&"f28_owned_commit_rejection".equals(e.getServerErrorMessage().getConstraint()))matched=true;if(next.getCause()!=null)queue.add(next.getCause());if(next instanceof SQLException e&&e.getNextException()!=null)queue.add(e.getNextException());}assertThat(matched).as("Both managers must fail from the owned deferred constraint").isTrue();}
    private void user(long id)throws SQLException{try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")){s.setLong(1,id);s.setString(2,"f28-"+id+"@fixture.invalid");s.setString(3,"F28 synthetic");s.setString(4,"F28nickname"+id);s.setString(5,"GOOGLE");s.setString(6,"ROLE_USER");s.executeUpdate();users.add(id);}}
    private void member(long id)throws SQLException{if(!members.contains(id)){execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES("+id+")");members.add(id);}}
    private void note(long mailbox,String direction,Instant expiry)throws SQLException{
        member(CALLER);member(TARGET);if(mailbox==OTHER)member(OTHER);
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES(?,?,?,?,?,?)")){s.setObject(1,NOTE);s.setLong(2,direction.equals("SENT")?CALLER:TARGET);s.setLong(3,direction.equals("SENT")?TARGET:CALLER);s.setString(4,"F28 owned synthetic note");s.setObject(5,OffsetDateTime.ofInstant(FIRST.minusSeconds(120),ZoneOffset.UTC));s.setObject(6,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC));s.executeUpdate();notes.add(NOTE);}
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES(?,?,?,?,?,?)")){s.setLong(1,mailbox);s.setObject(2,NOTE);s.setString(3,direction);s.setObject(4,direction.equals("SENT")?OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC):null);s.setBoolean(5,true);s.setObject(6,OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC));s.executeUpdate();}
    }
    private void seedScenario(String scenario)throws SQLException{
        user(CALLER);note(CALLER,scenario.startsWith("SENT")?"SENT":"INBOX",EXPIRY);
        if(scenario.endsWith("shared")){try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES(?,?,?,?,?,?)")){s.setLong(1,TARGET);s.setObject(2,NOTE);s.setString(3,"SENT");s.setObject(4,OffsetDateTime.ofInstant(FIRST.minusSeconds(30),ZoneOffset.UTC));s.setBoolean(5,true);s.setObject(6,OffsetDateTime.ofInstant(FIRST.minusSeconds(120),ZoneOffset.UTC));s.executeUpdate();}}
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,reply_to,created_at,expires_at) VALUES(?,?,?,?,?,?,?)")){s.setObject(1,CHILD);s.setLong(2,TARGET);s.setLong(3,CALLER);s.setString(4,"F28 owned child note");s.setObject(5,NOTE);s.setObject(6,OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC));s.setObject(7,OffsetDateTime.ofInstant(EXPIRY,ZoneOffset.UTC));s.executeUpdate();notes.add(CHILD);}
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_requests(sender_id,request_id,request_hash,note_id,created_at) VALUES(?,?,?,?,?)")){s.setLong(1,TARGET);s.setObject(2,REQUEST);s.setString(3,"f28-owned-request");s.setObject(4,NOTE);s.setObject(5,OffsetDateTime.ofInstant(FIRST,ZoneOffset.UTC));s.executeUpdate();requestOwned=true;}
    }
    private JsonNode json(String text){try{return new ObjectMapper().readTree(text);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("Owned snapshot JSON is invalid");}}
    private JsonNode expectedDeletion(String before,boolean shared){
        ObjectNode expected=(ObjectNode)json(before).deepCopy();ArrayNode mailboxes=(ArrayNode)expected.get("mailboxes");int removed=0;
        for(int i=mailboxes.size()-1;i>=0;i--){var row=mailboxes.get(i);if(row.get("member_id").asLong()==CALLER&&row.get("note_id").asText().equals(NOTE.toString())){mailboxes.remove(i);removed++;}}assertThat(removed).isOne();
        if(!shared){ArrayNode all=(ArrayNode)expected.get("private_notes");int originals=0,replies=0,requests=0;for(int i=all.size()-1;i>=0;i--){var row=all.get(i);if(row.get("note_id").asText().equals(NOTE.toString())){all.remove(i);originals++;}else if(row.get("reply_to").asText().equals(NOTE.toString())){assertThat(row.get("note_id").asText()).isEqualTo(CHILD.toString());((ObjectNode)row).putNull("reply_to");replies++;}}
            for(var row:expected.get("private_note_requests"))if(row.get("note_id").asText().equals(NOTE.toString())){assertThat(row.get("sender_id").asLong()).isEqualTo(TARGET);assertThat(row.get("request_id").asText()).isEqualTo(REQUEST.toString());((ObjectNode)row).putNull("note_id");requests++;}assertThat(originals).isOne();assertThat(replies).isOne();assertThat(requests).isOne();}
        return expected;
    }
    private void assertSameSnapshot(String expected)throws SQLException{assertThat(snapshot().equals(expected)).as("Exact seventeen-table snapshot identity without rendering private rows").isTrue();}
    private void assertSnapshot(JsonNode expected)throws SQLException{assertThat(json(snapshot()).equals(expected)).as("Exactly owned deletion/FK deltas across all seventeen tables, without rendering private rows").isTrue();}
    private void trackMembers()throws SQLException{for(long id:NAMESPACE)if(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)==1)members.add(id);}
    private static final class Samples extends Clock{private volatile Instant[]values={FIRST};private final AtomicInteger calls=new AtomicInteger();void sequence(Instant...values){this.values=values.clone();calls.set(0);}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return Clock.fixed(values[0],zone);}@Override public Instant instant(){int n=calls.getAndIncrement();return values[Math.min(n,values.length-1)];}}
    private static final class TrackingJdbc extends JdbcTemplate{private final AtomicInteger attempts=new AtomicInteger();private final List<Integer>rows=new CopyOnWriteArrayList<>();TrackingJdbc(javax.sql.DataSource ds){super(ds);}@Override public int update(String sql,Object...args){String normalized=sql.replaceAll("\\s+"," ").strip();boolean deletion=normalized.startsWith("DELETE FROM pawbridge_community.private_note_mailboxes ")||normalized.startsWith("DELETE FROM pawbridge_community.private_notes ");if(deletion)attempts.incrementAndGet();int n=super.update(sql,args);if(deletion)rows.add(n);return n;}}
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
        Path f28=receiptProperty("f28.fixture.receipt","f28-fixture-write.txt");assertThat(f28.getParent()).isEqualTo(f27.getParent());receipt(f28,f28Proof(run));
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
    private String f28Proof(String run){return "owner="+run+"\n"+"source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=F27 plus original requests and own-note mailbox deletion\nnew_business_ddl=0\nsynthetic_tables_added=1\nnew_schema=0\nnew_marker=0\nsnapshot_tables=17\nwriter_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);notes:SELECT(note_id,expires_at),UPDATE(note_id),DELETE;mailboxes:SELECT(member_id,note_id,direction,read_at),UPDATE(read_at,favorite),DELETE\nrequests_app_acl=0\nnew_sequence_acl=0\n3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119  ddl-source community-service/src/migration/resources/db/postgresql/V6__private_notes.sql\nf1dcd3476bf56eb7862ffcdbdaa0546a59c94a0fb637c04c7a5d6fb677da1cc6  ddl-source community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql\n0a794d8711da88e4c98ed98fe931d2b775dd29037169cd9c1655a34ad91d83bc  request-fragment-0\na7744fbfb946dfb2e7b42168a4dbf99a52bec4978d88dea24125007495b5822f  request-fragment-1\n65a253fa5f95bd592ff66373daf3fd7d41614c8c9a1aeaed5f6a6db071036fbc  request-fragment-2\n020b3e8baa54267e0699f878ccabd5cfbb68d35ff187d381491910315ec16ab0  request-fragment-3\n";}

    private void requireNicknamePrivilege()throws SQLException{assertThat(scalar("SELECT has_column_privilege('pawbridge_f2_reader','pawbridge_user.users','nickname','SELECT')::int")).isOne();}
    private void requireWriterPrivileges()throws SQLException{
        assertThat(scalar("SELECT (has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_members','member_id','UPDATE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','note_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','expires_at','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','note_id','UPDATE') AND has_table_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','DELETE') AND has_table_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','DELETE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','member_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','note_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','direction','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','read_at','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','read_at','UPDATE') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','favorite','UPDATE') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','favorite','SELECT') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','body','SELECT') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','expires_at','UPDATE'))::int")).isOne();
        for(String role:List.of("pawbridge_f2_reader","pawbridge_f3_writer")){
            assertThat(scalar("SELECT (count(*)=5 AND bool_and(NOT has_column_privilege('"+role+"','pawbridge_community.private_note_requests',column_name,'SELECT,INSERT,UPDATE,REFERENCES')))::int FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='private_note_requests'")).isOne();
            assertThat(scalar("SELECT (NOT has_table_privilege('"+role+"','pawbridge_community.private_note_requests','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))::int")).isOne();
        }
    }
    private void execute(String sql)throws SQLException{try(Connection c=admin();var s=c.createStatement()){s.execute(sql);}}
    private long scalar(String sql)throws SQLException{try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getLong(1);}}
    private String snapshot()throws SQLException{
        String[][]tables={
            {"private_notes","pawbridge_community.private_notes","note_id"},{"mailboxes","pawbridge_community.private_note_mailboxes","member_id,note_id"},
            {"pending","pawbridge_user.contact_deletions","user_id"},{"members","pawbridge_community.private_note_members","member_id"},{"blocks","pawbridge_community.private_note_blocks","member_id,blocked_id"},
            {"visits","pawbridge_community.daily_visit_count","visit_date"},{"videos","pawbridge_community.home_videos","id"},{"board","pawbridge_community.home_video_board","id"},
            {"users","pawbridge_user.users","user_id"},{"favorites","pawbridge_user.favorites","favorite_id"},{"posts","pawbridge_community.posts","post_id"},{"comments","pawbridge_community.comments","comment_id"},
            {"f4comments","pawbridge_f4_fixture.comments","comment_id"},{"notes","pawbridge_f4_fixture.private_notes","note_id"},{"outbox","pawbridge_community.outbox_events","outbox_id"},{"search","pawbridge_community.post_search_documents","post_id"},
            {"private_note_requests","pawbridge_community.private_note_requests","sender_id,request_id"}};
        assertThat(tables.length).isEqualTo(17);var sql=new StringBuilder("SELECT json_build_object(");
        for(int index=0;index<tables.length;index++){if(index>0)sql.append(',');String[]t=tables[index];sql.append("'").append(t[0]).append("',(SELECT COALESCE(json_agg(r ORDER BY ").append(t[2]).append("),'[]'::json) FROM ").append(t[1]).append(" r)");}
        sql.append(")::text");try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery(sql.toString())){assertThat(rows.next()).isTrue();String result=rows.getString(1);assertThat(json(result).size()).isEqualTo(17);return result;}
    }
    private void returned(){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        if(reader!=null){assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();}if(writer!=null){assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();}if(original!=null)assertThat(original.isResourceBound()).isFalse();
    }
}
