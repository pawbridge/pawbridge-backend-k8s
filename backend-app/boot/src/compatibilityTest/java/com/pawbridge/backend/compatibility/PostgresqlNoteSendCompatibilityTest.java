package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.*;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteSendStore;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
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
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** FROZEN DRAFT, NOT READY FOR INTEGRATION: no compile/test/PG run or completed independent static review. Original admin/JpaTM differs from restricted JdbcTM. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteSendCompatibilityTest {
 static final long CALLER=9_983_101L,TARGET=9_983_102L,OTHER=9_983_103L,POST=9_983_110L,REPORT=9_983_111L;
 static final String SOURCE="f06a72a06bd26662c647bde780dfc7a27bf7349c";
 static final Set<Long>NAMESPACE=Set.of(CALLER,TARGET,OTHER);
 static final Instant FIRST=Instant.parse("2032-02-29T15:00:00.123456Z"),EXPIRY=FIRST.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
 static final UUID KEY=UUID.fromString("f2901000-0000-0000-0000-000000000001"),KEY2=UUID.fromString("f2901000-0000-0000-0000-000000000002"),PARENT=UUID.fromString("f2901000-0000-0000-0000-000000000003"),NORMALIZED=UUID.fromString("f2901000-0000-0000-0000-000000000004");
 final Set<Long>users=new LinkedHashSet<>(),members=new LinkedHashSet<>(),pending=new LinkedHashSet<>();
 final Set<UUID>notes=ConcurrentHashMap.newKeySet(),requestKeys=ConcurrentHashMap.newKeySet();
 final List<Long>contacts=new CopyOnWriteArrayList<>();final List<Delivery>hints=new CopyOnWriteArrayList<>();
 final Samples sourceClock=new Samples(),candidateClock=new Samples();
 F3PostgresqlFixture owner;HikariDataSource reader,writer;TrackingJdbc writeJdbc;SendPrivateNote candidate;F29LegacyNoteSendOracle original;
 String baseline;boolean namespaceOwned,postOwned,reportOwned,throwHint;
 record Delivery(String side,long recipient,UUID noteId,Long actorId,String nickname,Instant createdAt,boolean read,String kind,String href){}
 @BeforeAll void openOwnedSourceJpaAndRestrictedCandidateWithIndependentReaderTransactions()throws Exception{
  assertThat(System.getProperty("user.timezone")).isIn("UTC","Asia/Seoul");owner=F3PostgresqlFixture.open();verifyOwnership();requireWriterPrivileges();
  try{reader=pool("f29-note-send-reader",true,owner.reads().applicationUser(),owner.reads().applicationPassword());writer=pool("f29-note-send-writer",false,F3PostgresqlFixture.WRITER,owner.writerPassword());
   var readJdbc=new JdbcTemplate(reader);readJdbc.setQueryTimeout(3);writeJdbc=new TrackingJdbc(writer);writeJdbc.setQueryTimeout(5);var readTM=new JdbcTransactionManager(reader);readTM.setEnforceReadOnly(true);var readTx=new TransactionTemplate(readTM);readTx.setReadOnly(true);readTx.setTimeout(3);var query=new JdbcContactMemberQuery(readJdbc,readTx);
   ContactMemberQuery contact=id->{contacts.add(id);assertThat(id).isIn(CALLER,TARGET);assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();try{return query.get(id);}finally{assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue();assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isOne();}};
   var tx=new TransactionTemplate(new JdbcTransactionManager(writer));tx.setTimeout(15);candidate=new SendPrivateNote(contact,new JdbcNoteSendStore(writeJdbc,tx,(id,n)->committed(new Delivery("candidate",id,n.noteId(),n.actorId(),n.actorNickname(),n.createdAt(),n.read(),n.kind(),n.href()))),candidateClock,UUID::randomUUID);
   original=new F29LegacyNoteSendOracle(owner,reader,sourceClock,(id,n)->committed(new Delivery("source",id,n.noteId(),n.actorId(),n.actorNickname(),n.createdAt(),n.read(),n.kind(),n.href())));returned();
  }catch(Exception|Error failure){closeOwnedOracleAndTwoPools();throw failure;}
 }
 private HikariDataSource pool(String name,boolean readOnly,String user,String password){var c=new HikariConfig();c.setJdbcUrl(owner.reads().jdbcUrl());c.setUsername(user);c.setPassword(password);c.setPoolName(name);c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setReadOnly(readOnly);c.setConnectionTimeout(2000);c.setValidationTimeout(1000);c.addDataSourceProperty("socketTimeout","20");c.addDataSourceProperty("ApplicationName",name);c.setConnectionInitSql("SET statement_timeout='18s'; SET lock_timeout='10s'");return new HikariDataSource(c);}
 @BeforeEach void reserveAbsentNamespaceBeforeEnablingCleanupAndSnapshotAllEighteenTables()throws Exception{
  namespaceOwned=false;baseline=null;verifyOwnership();requireWriterPrivileges();returned();reset();throwHint=false;
  for(long id:NAMESPACE){for(String pair:List.of("pawbridge_user.users:user_id","pawbridge_user.contact_deletions:user_id","pawbridge_community.private_note_members:member_id")){var p=pair.split(":");assertThat(scalar("SELECT count(*) FROM "+p[0]+" WHERE "+p[1]+"="+id)).isZero();}assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id="+id+" OR recipient_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id="+id+" OR blocked_id="+id)).isZero();}
  assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE post_id="+POST)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.animal_reports WHERE report_id="+REPORT)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id='"+PARENT+"'")).isZero();
  for(UUID id:List.of(KEY,KEY2))assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id='"+id+"'")).isZero();baseline=snapshot();namespaceOwned=true;
 }
 @AfterEach void cleanupOnlyAcknowledgedOwnedKeysAndRestoreAllEighteenTables()throws Exception{if(owner!=null&&namespaceOwned){cleanup();namespaceOwned=false;}}
 @AfterAll void closeOwnedOracleAndTwoPools(){try{if(original!=null)original.close();}finally{try{if(reader!=null)reader.close();}finally{if(writer!=null)writer.close();}}}

 @Test void givenMicrosecondLeapYearSend__whenPersistingAndSerializingInEitherZone__thenKeepOneInstantAcrossBothMailboxesRequestAndNotification()throws Exception{
  JsonNode first=null;for(boolean source:new boolean[]{true,false}){seedUsers();String before=snapshot();UUID id=send(source,request(KEY));returned();assertGenerated(before,id,KEY,null,null);assertThat(source?original.contactIds():contacts).containsExactly(CALLER,TARGET);assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(1);assertThat(hints).hasSize(1);assertThat(hints.getFirst().createdAt()).isEqualTo(FIRST);assertThat(hints.getFirst().nickname()).isEqualTo("F29nickname"+CALLER);
   try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){var mapper=context.getBean(ObjectMapper.class);var n=hints.getFirst();var payload=new NoteNotifications.Notification(n.noteId(),n.kind(),n.actorId(),n.nickname(),n.createdAt(),n.read(),n.href());var encoded=mapper.readTree(mapper.writeValueAsString(payload));assertThat(encoded.size()).isEqualTo(7);assertThat(encoded.get("createdAt").asText()).isEqualTo(FIRST.toString());assertThat(encoded.get("read").asBoolean()).isFalse();}
   JsonNode state=normalized(snapshot(),id);if(first==null)first=state;else assertThat(state.equals(first)).as("Only each independently verified generated UUID is normalized").isTrue();cleanup();reset();
  }
 }
 @ParameterizedTest @ValueSource(strings={"same","conflict","gone-note","gone-hash"})
 void givenExistingRequestAndCurrentMembers__whenSendingAgain__thenApplyGoneConflictReplayBeforeBlockClockOrNewRows(String mode)throws Exception{
  for(boolean source:new boolean[]{true,false}){seedUsers();UUID id=send(source,request(KEY));returned();notes.add(id);
   if(mode.equals("gone-note"))execute("UPDATE pawbridge_community.private_note_requests SET note_id=NULL WHERE sender_id="+CALLER+" AND request_id='"+KEY+"'");
   if(mode.equals("gone-hash"))execute("UPDATE pawbridge_community.private_note_requests SET request_hash=NULL WHERE sender_id="+CALLER+" AND request_id='"+KEY+"'");
   execute("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES("+CALLER+","+TARGET+",'"+FIRST+"')");String before=snapshot();reset();var req=mode.equals("conflict")?new SendPrivateNote.SendNote(TARGET,"different",KEY,null,null,null):request(KEY);
   if(mode.equals("same"))assertThat(send(source,req)).isEqualTo(id);else rejected(()->send(source,req),source,mode.startsWith("gone")?410:409,mode.startsWith("gone")?"GONE":"CONFLICT");returned();assertSame(before);assertThat(hints).isEmpty();assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(0);assertThat(source?original.contactIds():contacts).containsExactly(CALLER,TARGET);cleanup();reset();
  }
 }
 @ParameterizedTest @ValueSource(strings={"outgoing","reverse"})
 void givenNewKeyAndEitherBlockDirection__whenSending__thenRejectWithoutClockOrWrites(String direction)throws Exception{
  for(boolean source:new boolean[]{true,false}){seedUsers();member(CALLER);member(TARGET);long a=direction.equals("outgoing")?CALLER:TARGET,b=a==CALLER?TARGET:CALLER;execute("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES("+a+","+b+",'"+FIRST+"')");String before=snapshot();rejected(()->send(source,request(KEY)),source,403,"BLOCKED");returned();assertSame(before);assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(0);assertThat(hints).isEmpty();cleanup();reset();}
 }
 @ParameterizedTest @ValueSource(strings={"minute","day"})
 void givenInclusiveRequestLimitsIncludingOrphans__whenSendingNewKey__thenKeepMinuteFirstAndSeoulDayBoundaries(String limit)throws Exception{
  for(boolean source:new boolean[]{true,false}){seedUsers();member(CALLER);member(TARGET);int count=limit.equals("minute")?10:100;Instant at=limit.equals("minute")?FIRST.minusSeconds(60):FIRST.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
   for(int i=0;i<count;i++)seedRequest(key(100+i),null,"owned-orphan",at);if(limit.equals("day")){sourceClock.reset(FIRST.plusSeconds(120));candidateClock.reset(FIRST.plusSeconds(120));}String before=snapshot();rejected(()->send(source,request(KEY)),source,429,"RATE_LIMIT");returned();assertSame(before);assertThat(hints).isEmpty();assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(1);cleanup();reset();}
 }
 @ParameterizedTest @ValueSource(strings={"valid","expired","wrong-direction"})
 void givenReplyMailboxAndASecondClockSample__whenSending__thenKeepStrictExpiryAndInboxSenderPolicyBeforeInsert(String mode)throws Exception{
  for(boolean source:new boolean[]{true,false}){seedUsers();seedParent(mode.equals("wrong-direction")?"SENT":"INBOX",mode.equals("expired")?FIRST:FIRST.plusSeconds(120));String before=snapshot();var req=new SendPrivateNote.SendNote(TARGET,"  F29 private\n본문🦊  ",KEY,PARENT,null,null);
   if(mode.equals("valid")){UUID id=send(source,req);assertGenerated(before,id,KEY,PARENT,null);}else{rejected(()->send(source,req),source,mode.equals("expired")?404:400,mode.equals("expired")?"NOT_FOUND":"REPLY_MISMATCH");assertSame(before);}returned();assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(2);cleanup();reset();}
 }
 @ParameterizedTest @ValueSource(strings={"POST","REPORT"})
 void givenValidatedContextAuthorAndSoftDeletion__whenSending__thenReadOnlyTheChosenTableAndRejectTheDeletedAuthor(String kind)throws Exception{
  for(boolean source:new boolean[]{true,false}){seedUsers();seedContext(kind);var req=new SendPrivateNote.SendNote(TARGET,"F29 private\n본문🦊",KEY,null,kind,kind.equals("POST")?POST:REPORT);String before=snapshot();UUID id=send(source,req);assertGenerated(before,id,KEY,null,kind);returned();cleanup();reset();seedUsers();seedContext(kind);execute("UPDATE pawbridge_community."+(kind.equals("POST")?"posts SET deleted_at=CURRENT_TIMESTAMP WHERE post_id=":"animal_reports SET deleted_at=CURRENT_TIMESTAMP WHERE report_id=")+(kind.equals("POST")?POST:REPORT));String rejectedBefore=snapshot();rejected(()->send(source,req),source,400,"CONTEXT_AUTHOR");returned();assertSame(rejectedBefore);assertThat(hints).isEmpty();cleanup();reset();}
 }
 @ParameterizedTest @ValueSource(strings={"note-insert","mailbox-insert","request-insert","report-context","nickname"})
 void givenExactCandidateColumnPrivilegeRevocation__whenSending__thenRollbackAllPriorInsertsAndRestoreOnlyAfterOwnedReceipts(String kind)throws Exception{
  seedUsers();member(CALLER);member(TARGET);if(kind.equals("report-context"))seedContext("REPORT");String before=snapshot();var req=kind.equals("report-context")?new SendPrivateNote.SendNote(TARGET,"F29 private\n본문🦊",KEY,null,"REPORT",REPORT):request(KEY);var spec=privilege(kind);verifyOwnership();execute("REVOKE "+spec[0]+" ON "+spec[1]+" FROM "+spec[2]);
  try{RuntimeException failure=failure(()->send(false,req));if(kind.equals("nickname"))assertThat(failure).isInstanceOf(ContactMemberUnavailableException.class);else assertThat(failure).isInstanceOf(DataAccessException.class);returned();assertSame(before);assertThat(hints).isEmpty();}
  finally{verifyOwnership();execute("GRANT "+spec[0]+" ON "+spec[1]+" TO "+spec[2]);requireWriterPrivileges();}
  reset();String restored=snapshot();UUID id=send(false,req);returned();assertGenerated(restored,id,KEY,null,kind.equals("report-context")?"REPORT":null);
 }
 @ParameterizedTest @ValueSource(strings={"current-missing","target-pending"})
 void givenUnavailableCurrentOrPendingTarget__whenBothSend__thenReturn403AndRollbackMemberInsertionsBeforeClock(String kind)throws Exception{
  for(boolean source:new boolean[]{true,false}){if(!kind.equals("current-missing")){seedUsers();execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES("+TARGET+")");pending.add(TARGET);}String before=snapshot();rejected(()->send(source,request(KEY)),source,403,"INACTIVE");returned();assertSame(before);assertThat(source?original.contactIds():contacts).containsExactlyElementsOf(kind.equals("current-missing")?List.of(CALLER):List.of(CALLER,TARGET));assertThat(source?sourceClock.calls:candidateClock.calls).hasValue(0);assertThat(hints).isEmpty();cleanup();reset();}
 }
 @ParameterizedTest @ValueSource(strings={"same-key","rate-nine"})
 void givenTwoIndependentWritersBlockedOnOneMember__whenReleased__thenSerializeReplayOrTheTenthRequestWithoutPrematureUserClockOrHints(String kind)throws Exception{
  seedUsers();member(CALLER);member(TARGET);if(kind.equals("rate-nine"))for(int i=0;i<9;i++)seedRequest(key(200+i),null,"owned-rate",FIRST.minusSeconds(30));String before=snapshot();var executor=Executors.newFixedThreadPool(2);
  try(Connection blocker=admin()){blocker.setAutoCommit(false);try(var s=blocker.createStatement()){s.executeQuery("SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id="+CALLER+" FOR UPDATE").close();}
   var a=executor.submit(()->attempt(true,request(KEY)));var b=executor.submit(()->attempt(false,request(kind.equals("same-key")?KEY:KEY2)));
   try{awaitBlocked("f29-original-note-send");awaitBlocked("f29-note-send-writer");assertThat(original.contactIds()).isEmpty();assertThat(contacts).isEmpty();assertThat(sourceClock.calls).hasValue(0);assertThat(candidateClock.calls).hasValue(0);assertThat(hints).isEmpty();blocker.commit();var x=a.get(12,TimeUnit.SECONDS);var y=b.get(12,TimeUnit.SECONDS);returned();
    if(kind.equals("same-key")){assertThat(x.error).isNull();assertThat(y.error).isNull();assertThat(x.id).isEqualTo(y.id);assertGenerated(before,x.id,KEY,null,null);}else{var winner=x.error==null?x:y;var loser=x.error==null?y:x;assertThat(winner.id).isNotNull();assertThat(loser.error).isNotNull();assertStatus(loser.error,x.error!=null,429,"RATE_LIMIT");assertGenerated(before,winner.id,x.error==null?KEY:KEY2,null,null);}assertThat(hints).hasSize(1);
   }finally{blocker.rollback();}
  }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
 }
 @Test void givenOwnedDeferredCommitConstraint__whenBothInsertAllRows__thenRollbackEveryTableAndNeverPublish()throws Exception{
  verifyOwnership();assertThat(scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='pawbridge_community' AND p.proname='f29_owned_commit_rejection'")).isZero();boolean function=false,trigger=false;
  try{execute("CREATE FUNCTION pawbridge_community.f29_owned_commit_rejection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned synthetic constraint' USING ERRCODE='23514',CONSTRAINT='f29_owned_commit_rejection'; END $$");function=true;execute("CREATE CONSTRAINT TRIGGER f29_owned_commit_rejection AFTER INSERT ON pawbridge_community.private_note_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.sender_id="+CALLER+") EXECUTE FUNCTION pawbridge_community.f29_owned_commit_rejection()");trigger=true;
   var failures=new ArrayList<RuntimeException>();
   for(boolean source:new boolean[]{true,false}){seedUsers();String before=snapshot();var e=failure(()->send(source,request(KEY)));requireOwnedCommitCause(e);failures.add(e);returned();assertSame(before);assertThat(hints).isEmpty();assertThat(source?original.insertAttempts():writeJdbc.attempts.get()).isEqualTo(3);cleanup();reset();}
   // Resolve the captured manager failures with the actual controllers/advice, without a test-side classifier.
   var mvc=new F29MvcFixture();
   org.mockito.Mockito.doThrow(failures.get(0)).when(mvc.original).send(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
   org.mockito.Mockito.doThrow(failures.get(1)).when(mvc.candidate).send(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
   try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
    var mapper=context.getBean(ObjectMapper.class);
    var sourceResponse=mvc.send(mvc.sourceMvc(mapper),F29MvcFixture.CANONICAL,false);
    var candidateResponse=mvc.send(mvc.candidateMvc(mapper),F29MvcFixture.CANONICAL,true);
    assertThat(sourceResponse.getResolvedException()==failures.get(0)).as("Original scoped advice receives the actual JpaTM commit failure").isTrue();
    assertThat(candidateResponse.getResolvedException()==failures.get(1)).as("Candidate scoped advice receives the actual JdbcTM commit failure").isTrue();
    for(var response:List.of(sourceResponse,candidateResponse)){assertThat(response.getResponse().getStatus()).isEqualTo(503);F29MvcFixture.cache(response);}
    assertThat(mapper.readTree(F29MvcFixture.body(candidateResponse))).as("Actual manager failures must retain the same HTTP message family without normalizing it").isEqualTo(mapper.readTree(F29MvcFixture.body(sourceResponse)));
   }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }finally{verifyOwnership();if(trigger)execute("DROP TRIGGER f29_owned_commit_rejection ON pawbridge_community.private_note_requests");if(function)execute("DROP FUNCTION pawbridge_community.f29_owned_commit_rejection()");}
 }
 @Test void givenRuntimeInCommittedPublish__whenBothSend__thenIndependentConnectionSeesAllFourRowsAndSuccessRemains()throws Exception{
  for(boolean source:new boolean[]{true,false}){seedUsers();String before=snapshot();throwHint=true;UUID id=send(source,request(KEY));returned();assertGenerated(before,id,KEY,null,null);assertThat(hints).hasSize(1);cleanup();reset();}throwHint=false;
 }

 private UUID send(boolean source,SendPrivateNote.SendNote r){requestKeys.add(r.requestId());try{return source?original.send(CALLER,new PrivateNoteModels.SendNote(r.recipientId(),r.body(),r.requestId(),r.replyTo(),r.contextType(),r.contextId())):candidate.send(CALLER,r).noteId();}finally{trackAcknowledgedNotes();}}
 private SendPrivateNote.SendNote request(UUID key){return new SendPrivateNote.SendNote(TARGET,"  F29 private\n본문🦊  ",key,null,null,null);}
 private void trackAcknowledgedNotes(){notes.addAll(original.insertedIds());notes.addAll(writeJdbc.inserted);}
 private void reset(){contacts.clear();hints.clear();sourceClock.reset(FIRST);candidateClock.reset(FIRST);if(original!=null)original.resetObservations();if(writeJdbc!=null){writeJdbc.attempts.set(0);writeJdbc.inserted.clear();}}
 private void committed(Delivery d){
  assertThat(d.recipient()).isEqualTo(TARGET);assertThat(d.actorId()).isEqualTo(CALLER);assertThat(d.kind()).isEqualTo("PRIVATE_NOTE");assertThat(d.read()).isFalse();assertThat(d.href()).isEqualTo("/notes/"+d.noteId());
  try{assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id='"+d.noteId()+"'")).as("Independent connection sees committed note").isOne();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+d.noteId()+"'")).isEqualTo(2);assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE note_id='"+d.noteId()+"'")).isOne();}catch(SQLException e){throw new IllegalStateException("Owned commit visibility read failed");}hints.add(d);if(throwHint)throw new IllegalStateException("synthetic postcommit push failure");
 }
 record Attempt(UUID id,RuntimeException error){}
 private Attempt attempt(boolean source,SendPrivateNote.SendNote r){try{return new Attempt(send(source,r),null);}catch(RuntimeException e){return new Attempt(null,e);}finally{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();}}
 private RuntimeException failure(Runnable r){try{r.run();}catch(RuntimeException e){return e;}throw new AssertionError("Expected the selected source/candidate operation to fail");}
 private void rejected(Runnable r,boolean source,int status,String reason){assertStatus(failure(r),source,status,reason);}
 private void assertStatus(RuntimeException e,boolean source,int status,String reason){if(source){assertThat(e).isInstanceOf(ResponseStatusException.class);assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(status);}else{assertThat(e).isInstanceOf(NoteSendRejectedException.class);assertThat(((NoteSendRejectedException)e).reason().name()).isEqualTo(reason);}}
 private void awaitBlocked(String application)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+application+"' AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid))>0")>0)return;Thread.sleep(20);}throw new AssertionError("Expected an actual owned member-row lock wait");}
 private void requireOwnedCommitCause(Throwable e){var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var q=new ArrayDeque<Throwable>();q.add(e);boolean found=false;while(!q.isEmpty()&&seen.size()<32){var x=q.removeFirst();if(!seen.add(x))continue;if(x instanceof PSQLException p&&"23514".equals(p.getSQLState())&&p.getServerErrorMessage()!=null&&"f29_owned_commit_rejection".equals(p.getServerErrorMessage().getConstraint()))found=true;if(x.getCause()!=null)q.add(x.getCause());if(x instanceof SQLException p&&p.getNextException()!=null)q.add(p.getNextException());}assertThat(found).as("Commit must fail from the owned deferred constraint, without printing exception details").isTrue();}
 private String[]privilege(String kind){return switch(kind){case "note-insert"->new String[]{"INSERT(body)","pawbridge_community.private_notes","pawbridge_f3_writer"};case "mailbox-insert"->new String[]{"INSERT(direction)","pawbridge_community.private_note_mailboxes","pawbridge_f3_writer"};case "request-insert"->new String[]{"INSERT(request_hash)","pawbridge_community.private_note_requests","pawbridge_f3_writer"};case "report-context"->new String[]{"SELECT(author_id)","pawbridge_community.animal_reports","pawbridge_f3_writer"};case "nickname"->new String[]{"SELECT(nickname)","pawbridge_user.users","pawbridge_f2_reader"};default->throw new IllegalArgumentException("Unknown fixed privilege case");};}
 private UUID key(int n){return UUID.fromString(String.format(java.util.Locale.ROOT,"f2901000-0000-0000-0000-%012d",n));}
 private void seedUsers()throws SQLException{user(CALLER);user(TARGET);}
 private void user(long id)throws SQLException{try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")){s.setLong(1,id);s.setString(2,"f29-"+id+"@fixture.invalid");s.setString(3,"F29 synthetic");s.setString(4,"F29nickname"+id);s.setString(5,"GOOGLE");s.setString(6,"ROLE_USER");s.executeUpdate();users.add(id);}}
 private void member(long id)throws SQLException{if(!members.contains(id)){execute("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES("+id+") ON CONFLICT DO NOTHING");members.add(id);}}
 private void seedRequest(UUID id,UUID note,String hash,Instant created)throws SQLException{assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id='"+id+"'")).isZero();try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_requests(sender_id,request_id,request_hash,note_id,created_at) VALUES(?,?,?,?,?)")){s.setLong(1,CALLER);s.setObject(2,id);s.setString(3,hash);s.setObject(4,note);s.setObject(5,OffsetDateTime.ofInstant(created,ZoneOffset.UTC));s.executeUpdate();requestKeys.add(id);}}
 private void seedParent(String direction,Instant expiry)throws SQLException{member(CALLER);member(TARGET);try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES(?,?,?,?,?,?)")){s.setObject(1,PARENT);s.setLong(2,direction.equals("SENT")?CALLER:TARGET);s.setLong(3,direction.equals("SENT")?TARGET:CALLER);s.setString(4,"F29 parent");s.setObject(5,OffsetDateTime.ofInstant(FIRST.minusSeconds(120),ZoneOffset.UTC));s.setObject(6,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC));s.executeUpdate();notes.add(PARENT);}execute("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,created_at) VALUES("+CALLER+",'"+PARENT+"','"+direction+"','"+FIRST+"')");}
 private void seedContext(String kind)throws SQLException{if(kind.equals("POST")){execute("INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,image_urls,created_at,updated_at) VALUES("+POST+","+TARGET+",'F29 synthetic','F29 synthetic','COMMUNICATION','[]','2032-02-29 15:00:00.123456','2032-02-29 15:00:00.123456')");postOwned=true;}else{execute("INSERT INTO pawbridge_community.animal_reports(report_id,author_id,report_kind,description,occurred_on,region,species,created_at,updated_at) VALUES("+REPORT+","+TARGET+",'MISSING','F29 synthetic','2032-02-29','synthetic','DOG','2032-02-29 15:00:00.123456','2032-02-29 15:00:00.123456')");reportOwned=true;}}
 private void cleanup()throws Exception{
  verifyOwnership();returned();trackAcknowledgedNotes();String ids=NAMESPACE.stream().map(String::valueOf).sorted().collect(java.util.stream.Collectors.joining(","));for(long id:NAMESPACE)if(scalar("SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id)==1)members.add(id);
  for(UUID id:notes){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+id+"' AND member_id NOT IN ("+ids+")")).isZero();String ownedNotes=uuidList(notes);assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE reply_to='"+id+"' AND note_id NOT IN ("+ownedNotes+")")).isZero();String ownedRequests=uuidList(requestKeys);assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE note_id='"+id+"' AND NOT(sender_id="+CALLER+" AND request_id IN ("+ownedRequests+"))")).isZero();}
  for(UUID key:requestKeys){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id='"+key+"' AND sender_id<>"+CALLER)).isZero();execute("DELETE FROM pawbridge_community.private_note_requests WHERE sender_id="+CALLER+" AND request_id='"+key+"'");}
  if(!notes.isEmpty()){execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE note_id IN ("+uuidList(notes)+") AND member_id IN ("+ids+")");execute("DELETE FROM pawbridge_community.private_notes WHERE note_id IN ("+uuidList(notes)+")");}
  assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE (member_id IN ("+ids+") OR blocked_id IN ("+ids+")) AND NOT(member_id IN ("+ids+") AND blocked_id IN ("+ids+"))")).isZero();execute("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id IN ("+ids+") AND blocked_id IN ("+ids+")");
  for(long id:members){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id="+id+" OR recipient_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id="+id)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id="+id)).isZero();execute("DELETE FROM pawbridge_community.private_note_members WHERE member_id="+id);}
  if(postOwned){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE context_type='POST' AND context_id="+POST)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.comments WHERE post_id="+POST)).isZero();assertThat(scalar("SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id="+POST)).isZero();execute("DELETE FROM pawbridge_community.posts WHERE post_id="+POST);postOwned=false;}if(reportOwned){assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE context_type='REPORT' AND context_id="+REPORT)).isZero();execute("DELETE FROM pawbridge_community.animal_reports WHERE report_id="+REPORT);reportOwned=false;}
  for(long id:pending)execute("DELETE FROM pawbridge_user.contact_deletions WHERE user_id="+id);for(long id:users)execute("DELETE FROM pawbridge_user.users WHERE user_id="+id);users.clear();members.clear();pending.clear();notes.clear();requestKeys.clear();assertSame(baseline);
 }
 private String uuidList(Collection<UUID>ids){return ids.isEmpty()?"NULL":ids.stream().map(x->"'"+x+"'").collect(java.util.stream.Collectors.joining(","));}
 private ObjectNode row(Object...pairs){var n=new ObjectMapper().createObjectNode();for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],new ObjectMapper().valueToTree(pairs[i+1]));return n;}
 private void assertGenerated(String before,UUID id,UUID request,UUID reply,String context)throws SQLException{
  assertThat(id).isNotNull();assertThat(hints).hasSize(1);assertThat(hints.getFirst().noteId()).isEqualTo(id);assertThat(hints.getFirst().createdAt()).isEqualTo(FIRST);notes.add(id);
  ObjectNode expected=(ObjectNode)json(before).deepCopy();var allMembers=(ArrayNode)expected.get("members");for(long user:List.of(CALLER,TARGET)){boolean found=false;for(var r:allMembers)if(r.get("member_id").asLong()==user)found=true;if(!found)allMembers.add(row("member_id",user));}
  ((ArrayNode)expected.get("private_notes")).add(row("note_id",id.toString(),"sender_id",CALLER,"recipient_id",TARGET,"body","F29 private\n본문🦊","reply_to",reply==null?null:reply.toString(),"context_type",context,"context_id",context==null?null:context.equals("POST")?POST:REPORT,"created_at",FIRST.toString(),"expires_at",EXPIRY.toString()));
  var boxes=(ArrayNode)expected.get("mailboxes");boxes.add(row("member_id",CALLER,"note_id",id.toString(),"direction","SENT","read_at",FIRST.toString(),"favorite",false,"created_at",FIRST.toString()));boxes.add(row("member_id",TARGET,"note_id",id.toString(),"direction","INBOX","read_at",null,"favorite",false,"created_at",FIRST.toString()));
  ((ArrayNode)expected.get("private_note_requests")).add(row("sender_id",CALLER,"request_id",request.toString(),"request_hash",hash(new SendPrivateNote.SendNote(TARGET,"F29 private\n본문🦊",request,reply,context,context==null?null:context.equals("POST")?POST:REPORT)),"note_id",id.toString(),"created_at",FIRST.toString()));
  assertThat(canonical(json(snapshot())).equals(canonical(json(expected.toString())))).as("Exactly note1/mailbox2/request1 and previously absent member rows; other eighteen-table values unchanged").isTrue();
 }
 private String hash(SendPrivateNote.SendNote r){try{String raw=r.recipientId()+"\n"+r.replyTo()+"\n"+r.contextType()+"\n"+r.contextId()+"\n"+r.body().strip();return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private JsonNode json(String text){try{return new ObjectMapper().readTree(text);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Owned snapshot JSON is invalid");}}
 private JsonNode canonical(JsonNode source){var v=source.deepCopy();for(String table:List.of("private_notes","mailboxes","private_note_requests","blocks","pending")){for(var r:v.get(table)){for(String field:List.of("created_at","expires_at","read_at","started_at")){var t=r.get(field);if(t!=null&&!t.isNull())((ObjectNode)r).put(field,OffsetDateTime.parse(t.asText()).toInstant().toString());}}}
  for(String table:List.of("private_notes","mailboxes","private_note_requests","members")){String[]keys=switch(table){case "private_notes"->new String[]{"note_id"};case "mailboxes"->new String[]{"member_id","note_id"};case "private_note_requests"->new String[]{"sender_id","request_id"};default->new String[]{"member_id"};};var a=(ArrayNode)v.get(table);var list=new ArrayList<JsonNode>();a.forEach(list::add);list.sort((x,y)->{for(String key:keys){int c=x.get(key).isNumber()?Long.compare(x.get(key).asLong(),y.get(key).asLong()):x.get(key).asText().compareTo(y.get(key).asText());if(c!=0)return c;}return 0;});a.removeAll();list.forEach(a::add);}return v;
 }
 private JsonNode normalized(String snapshot,UUID id){var n=(ObjectNode)canonical(json(snapshot));for(String table:List.of("private_notes","mailboxes","private_note_requests"))for(var r:n.get(table)){for(String field:List.of("note_id","reply_to"))if(r.hasNonNull(field)&&r.get(field).asText().equals(id.toString()))((ObjectNode)r).put(field,NORMALIZED.toString());}return canonical(n);}
 private void assertSame(String before)throws SQLException{assertThat(snapshot().equals(before)).as("Whole eighteen-table state unchanged without rendering private values").isTrue();}
 static final class Samples extends Clock{volatile Instant value=FIRST;final AtomicInteger calls=new AtomicInteger();void reset(Instant v){value=v;calls.set(0);}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(value,z);}public Instant instant(){calls.incrementAndGet();return value;}}
 static final class TrackingJdbc extends JdbcTemplate{final AtomicInteger attempts=new AtomicInteger();final List<UUID>inserted=new CopyOnWriteArrayList<>();TrackingJdbc(javax.sql.DataSource ds){super(ds);}public int update(String sql,Object...a){String s=sql.replaceAll("\\s+"," ").strip();boolean insertion=s.startsWith("INSERT INTO pawbridge_community.private_notes(")||s.startsWith("INSERT INTO pawbridge_community.private_note_mailboxes(")||s.startsWith("INSERT INTO pawbridge_community.private_note_requests(");if(insertion)attempts.incrementAndGet();int rows=super.update(sql,a);if(s.startsWith("INSERT INTO pawbridge_community.private_notes(")&&rows==1)inserted.add((UUID)a[0]);return rows;}}

    @Test @Order(Integer.MAX_VALUE)
    void givenOwnedIssuerAndPriorCredentials__whenRefreshingOnlyF29Last__thenKeepTwoNormalSixHundredSecondTokensAndAllEarlierKeys()throws Exception{
        verifyOwnership();String run=runOwner();Path path=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();assertThat(path).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));assertThat(Files.isSymbolicLink(path)).isFalse();assertThat(Files.isRegularFile(path)).isTrue();assertThat(path.toRealPath()).isEqualTo(path);assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper();Map<String,String>tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(path),new TypeReference<Map<String,String>>(){}));var prior=Map.copyOf(tokens);String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");var key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));var issuer=new JwtProvider(secret,600_000,600_000);var ids=new LinkedHashMap<String,Long>();ids.put("F29_CALLER",9_983_001L);ids.put("F29_OTHER",9_983_002L);
        for(var entry:ids.entrySet()){long id=entry.getValue();var user=User.builder().userId(id).email("f29-token-"+id+"@fixture.invalid").name("F29 synthetic").nickname("F29token"+id).provider("GOOGLE").role(Role.ROLE_USER).build();String token=issuer.createAccessToken(user);assertThat(new PostTokenAuthenticator(key).authenticate("Bearer "+token)).isEqualTo(id);var claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();assertThat(claims.getExpiration().getTime()-claims.getIssuedAt().getTime()).isEqualTo(600_000L);tokens.put(entry.getKey(),token);}
        for(var e:prior.entrySet())if(!ids.containsKey(e.getKey()))assertThat(Objects.equals(tokens.get(e.getKey()),e.getValue())).as("Earlier credentials stay unchanged without rendering them").isTrue();assertThat(tokens.keySet()).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(prior.keySet().stream(),ids.keySet().stream()).collect(java.util.stream.Collectors.toSet()));Files.writeString(path,mapper.writeValueAsString(tokens));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));returned();
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
        Path f28=receiptProperty("f28.fixture.receipt","f28-fixture-write.txt");assertThat(f28.getParent()).isEqualTo(f27.getParent());receipt(f28,f28Proof(run));
        Path f29=receiptProperty("f29.fixture.receipt","f29-fixture-write.txt");assertThat(f29.getParent()).isEqualTo(f28.getParent());receipt(f29,f29Proof(run));
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

    private String f29Proof(String run){return "owner="+run+"\n"+"source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=F28 plus pinned report context and exact note-send columns\nnew_business_ddl=0\nsynthetic_tables_added=1\nsynthetic_identity_sequences_added=1\nnew_schema=0\nnew_marker=0\nsnapshot_tables=18\nreader_delta=0\nwriter_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);notes:SELECT(note_id,expires_at,sender_id),INSERT(note_id,sender_id,recipient_id,body,reply_to,context_type,context_id,created_at,expires_at),UPDATE(note_id),DELETE;mailboxes:SELECT(member_id,note_id,direction,read_at),INSERT(member_id,note_id,direction,read_at,created_at),UPDATE(read_at,favorite),DELETE;requests:SELECT(sender_id,request_id,request_hash,note_id,created_at),INSERT(sender_id,request_id,request_hash,note_id,created_at);reports:SELECT(report_id,author_id,deleted_at)\nreports_reader_acl=0\nnew_sequence_acl=0\n6f1d2a9c75e1fb2ec836f5e4cd8fecbf68fd0c2018a20a79193edd27747dd71a  ddl-source community-service/src/migration/resources/db/postgresql/V4__animal_reports.sql\nff29faf3026342725bf585cb5feebf33f617a34c14e0b038c35ea29276c1f213  ddl-source community-service/src/migration/resources/db/postgresql/V5__animal_report_search_fields.sql\n22b35d7107312096c32c3ae7023a7e78fd6d97d396c286bdae17bb1b50b9c8ea  report-fragment-0\n8896b1fa54b0859fe32deef8fc7d959d69a7c297e185692e4018d5121c4294e7  report-fragment-1\nbd354d203af70dc5afaf89b25d78322cd751a9c8e199d6ef7e2cba49df9f7b76  report-fragment-2\n83acfa67eaf9e0f0a4c75c48b393ca45020724d239d13269bef5cc4f5c68f198  report-fragment-3\n591e30398f83e66a3e77f01d1ff8fbb0e68bf8a1946c89c646ee6f99c622d707  report-fragment-4\n";}
    private void requireWriterPrivileges()throws SQLException{
        assertThat(scalar("SELECT (has_column_privilege('pawbridge_f2_reader','pawbridge_user.users','nickname','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','sender_id','SELECT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_notes','body','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_mailboxes','direction','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_requests','request_hash','INSERT') AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.animal_reports','author_id','SELECT'))::int")).isOne();
        for(String role:List.of("pawbridge_f2_reader","pawbridge_f3_writer"))assertThat(scalar("SELECT (NOT has_sequence_privilege('"+role+"','pawbridge_community.animal_reports_report_id_seq','USAGE,SELECT,UPDATE'))::int")).isOne();
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
            {"private_note_requests","pawbridge_community.private_note_requests","sender_id,request_id"},{"animal_reports","pawbridge_community.animal_reports","report_id"}};
        assertThat(tables.length).isEqualTo(18);var sql=new StringBuilder("SELECT json_build_object(");
        for(int index=0;index<tables.length;index++){if(index>0)sql.append(',');String[]t=tables[index];sql.append("'").append(t[0]).append("',(SELECT COALESCE(json_agg(r ORDER BY ").append(t[2]).append("),'[]'::json) FROM ").append(t[1]).append(" r)");}
        sql.append(")::text");try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery(sql.toString())){assertThat(rows.next()).isTrue();String result=rows.getString(1);assertThat(json(result).size()).isEqualTo(18);return result;}
    }
    private void returned(){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        if(reader!=null){assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();}if(writer!=null){assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();}if(original!=null)assertThat(original.isResourceBound()).isFalse();
    }

 // F30 only: selected by note-expiry tag; every pre-existing F29 method above remains byte-for-byte unchanged.
 private static final String EXPIRY_BODY="F30 synthetic expiry";
 private final Set<UUID> expiryReservedNotes=new LinkedHashSet<>(),expiryReservedRequests=new LinkedHashSet<>();
 private Instant expiryNow;
 private record ExpiryNote(UUID id,Instant expires,UUID replyTo,boolean bothMailboxes){}
 private record ExpiryRequest(UUID id,UUID note,Instant created){}

 @Test @Tag("note-expiry")
 void givenOwnedExpiryAndFortyEightHourBoundaries__whenBothExpireTwice__thenKeepForeignBaselineAndApplyFkThenOrphanCleanupEvenWhenFirstDeleteIsZero()throws Exception{
  beginExpiry();
  try(var privileges=expiryPrivileges()){
   for(boolean source:new boolean[]{true,false}){
    UUID equal=expiryId(1),before=expiryId(2),live=expiryId(3),child=expiryId(4);
    UUID linkedOld=expiryId(101),exact=expiryId(102),orphanOld=expiryId(103),fresh=expiryId(104),liveLinked=expiryId(105);
    try{
     seedExpiry(List.of(new ExpiryNote(equal,expiryNow,null,true),new ExpiryNote(before,expiryNow.minusNanos(1000),null,false),
       new ExpiryNote(live,expiryNow.plusNanos(1000),null,false),new ExpiryNote(child,expiryNow.plusSeconds(60),equal,false)),
       List.of(new ExpiryRequest(linkedOld,equal,expiryNow.minusSeconds(172801)),new ExpiryRequest(exact,null,expiryNow.minusSeconds(172800)),
       new ExpiryRequest(orphanOld,null,expiryNow.minusSeconds(172801)),new ExpiryRequest(fresh,null,expiryNow.minusSeconds(1)),new ExpiryRequest(liveLinked,live,expiryNow.minusSeconds(172801))));
     String first=snapshot();expireOwned(source,2);assertExpiryDelta(first,Set.of(equal,before),Set.of(linkedOld,orphanOld));
     try(Connection c=admin();var s=c.prepareStatement("UPDATE pawbridge_community.private_note_requests SET created_at=? WHERE sender_id=? AND request_id=?")){
      s.setObject(1,OffsetDateTime.ofInstant(expiryNow.minusSeconds(172801),ZoneOffset.UTC));s.setLong(2,CALLER);s.setObject(3,fresh);assertThat(s.executeUpdate()).isOne();
     }
     String second=snapshot();expireOwned(source,0);assertExpiryDelta(second,Set.of(),Set.of(fresh));
    }finally{finishExpiryRound();}
   }
  }
 }

 @Test @Tag("note-expiry")
 void givenThreeHundredAndOneOwnedCandidatesPerTable__whenBothExpireOneBatch__thenDeleteExactlyTheOldestThreeHundredPerSqlWithoutDraining()throws Exception{
  beginExpiry();
  try(var privileges=expiryPrivileges()){
   for(boolean source:new boolean[]{true,false}){
    try{
     var ns=new ArrayList<ExpiryNote>();var rs=new ArrayList<ExpiryRequest>();var removedNotes=new LinkedHashSet<UUID>();var removedRequests=new LinkedHashSet<UUID>();
     for(int i=0;i<301;i++){
      UUID note=expiryId(1000+i),request=expiryId(2000+i);
      ns.add(new ExpiryNote(note,expiryNow.minusSeconds(301-i),null,false));rs.add(new ExpiryRequest(request,null,expiryNow.minusSeconds(172800+301-i)));
      if(i<300){removedNotes.add(note);removedRequests.add(request);}
     }
     seedExpiry(ns,rs);String before=snapshot();expireOwned(source,300);assertExpiryDelta(before,removedNotes,removedRequests);
    }finally{finishExpiryRound();}
   }
  }
 }

 @Test @Tag("note-expiry")
 void givenOneLockedOwnedNoteAndOrphanRequest__whenBothExpire__thenSkipThoseRowsAndDeleteTheUnlockedOwnedCandidates()throws Exception{
  beginExpiry();
  try(var privileges=expiryPrivileges()){
   for(boolean source:new boolean[]{true,false}){
    UUID locked=expiryId(11),free=expiryId(12),lockedRequest=expiryId(111),freeRequest=expiryId(112);
    try{
     seedExpiry(List.of(new ExpiryNote(locked,expiryNow.minusSeconds(2),null,false),new ExpiryNote(free,expiryNow.minusSeconds(1),null,false)),
       List.of(new ExpiryRequest(lockedRequest,null,expiryNow.minusSeconds(172802)),new ExpiryRequest(freeRequest,null,expiryNow.minusSeconds(172801))));
     String before=snapshot();
     try(Connection blocker=admin()){
      blocker.setAutoCommit(false);
      try(var s=blocker.createStatement()){
       s.executeQuery("SELECT note_id FROM pawbridge_community.private_notes WHERE note_id='"+locked+"' FOR UPDATE").close();
       s.executeQuery("SELECT request_id FROM pawbridge_community.private_note_requests WHERE sender_id="+CALLER+" AND request_id='"+lockedRequest+"' FOR UPDATE").close();
      }
      try{expireOwned(source,1);assertExpiryDelta(before,Set.of(free),Set.of(freeRequest));}finally{blocker.rollback();}
     }
    }finally{finishExpiryRound();}
   }
  }
 }

 @Test @Tag("note-expiry")
 void givenOwnedRequestDeletePermissionFailure__whenCandidateHasAlreadyDeletedTheExpiredNote__thenRollbackBothSqlAndRestoreOnlyOwnedPrivileges()throws Exception{
  beginExpiry();
  try(var privileges=expiryPrivileges()){
   try{
    UUID note=expiryId(21),request=expiryId(121);
    seedExpiry(List.of(new ExpiryNote(note,expiryNow,null,true)),List.of(new ExpiryRequest(request,note,expiryNow.minusSeconds(172801))));
    String before=snapshot();verifyOwnership();execute("REVOKE DELETE ON pawbridge_community.private_note_requests FROM pawbridge_f3_writer");
    try{assertThat(failure(()->expireOwned(false,-1))).isInstanceOf(DataAccessException.class);returned();assertSame(before);}
    finally{verifyOwnership();execute("GRANT DELETE ON pawbridge_community.private_note_requests TO pawbridge_f3_writer");requireExpiryPrivileges(true);}
   }finally{finishExpiryRound();}
  }
 }

 @Test @Tag("note-expiry")
 void givenAnOwnedDeferredDeleteConstraint__whenBothCommitExpiry__thenRejectFromThatConstraintAndRollbackAllEighteenTableChanges()throws Exception{
  beginExpiry();String name="f30_owned_expiry_rejection";verifyOwnership();
  assertThat(scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='pawbridge_community' AND p.proname='"+name+"'")).isZero();
  assertThat(scalar("SELECT count(*) FROM pg_trigger WHERE tgrelid='pawbridge_community.private_notes'::regclass AND tgname='"+name+"'")).isZero();
  boolean function=false,trigger=false;
  try(var privileges=expiryPrivileges()){
   try{
    execute("CREATE FUNCTION pawbridge_community."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned synthetic constraint' USING ERRCODE='23514',CONSTRAINT='"+name+"'; END $$");function=true;
    execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER DELETE ON pawbridge_community.private_notes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (OLD.sender_id="+CALLER+") EXECUTE FUNCTION pawbridge_community."+name+"()");trigger=true;
    for(boolean source:new boolean[]{true,false}){
     try{
      UUID note=expiryId(31),request=expiryId(131);seedExpiry(List.of(new ExpiryNote(note,expiryNow,null,true)),List.of(new ExpiryRequest(request,note,expiryNow.minusSeconds(172801))));
      String before=snapshot();requireOwnedExpiryCommitFailure(failure(()->expireOwned(source,-1)));returned();assertSame(before);assertThat(hints).isEmpty();assertThat(contacts).isEmpty();assertThat(original.contactIds()).isEmpty();
     }finally{
      // The deferred trigger must be removed before normal owned cleanup DELETEs.
      if(trigger){verifyOwnership();execute("DROP TRIGGER "+name+" ON pawbridge_community.private_notes");trigger=false;}
      finishExpiryRound();
     }
     if(source){execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER DELETE ON pawbridge_community.private_notes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (OLD.sender_id="+CALLER+") EXECUTE FUNCTION pawbridge_community."+name+"()");trigger=true;}
    }
   }finally{
    verifyOwnership();try{if(trigger)execute("DROP TRIGGER "+name+" ON pawbridge_community.private_notes");}
    finally{if(function)execute("DROP FUNCTION pawbridge_community."+name+"()");}
   }
  }
 }

 @Test @Tag("note-expiry") @Order(Integer.MAX_VALUE-1) @Timeout(45)
 void givenAnActiveExpiryWaitingForOwnedMailboxFk__whenActualBootSchedulerContextCloses__thenReturnTheTransactionBeforeWriterPoolDestructionAndNeverReschedule()throws Exception{
  beginExpiry();
  var released=new java.util.concurrent.atomic.AtomicBoolean();var observed=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
  var closeStarted=new CountDownLatch(1);
  var closer=Executors.newSingleThreadExecutor(action->{var t=new Thread(action,"f30-context-close");t.setDaemon(true);return t;});
  var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();Future<?> task=null,closing=null;
  try(var privileges=expiryPrivileges()){
   try{
    UUID note=expiryId(41);seedExpiry(List.of(new ExpiryNote(note,expiryNow,null,true)),List.of());String before=snapshot();requireNoForeignExpiryCandidates();
    candidateClock.reset(expiryNow);expiryObservationPhase="candidate-scheduler";
    context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("f30-owned-lifecycle",Map.of("pawbridge.jobs.cleanup-enabled","true","spring.lifecycle.timeout-per-shutdown-phase","10s")));
    context.registerBean("writeDataSource",HikariDataSource.class,()->writer,definition->((org.springframework.beans.factory.support.AbstractBeanDefinition)definition).setDestroyMethodName("close"));
    context.registerBean("writeJdbc",JdbcTemplate.class,()->writeJdbc);
    context.registerBean("writeTransactionManager",JdbcTransactionManager.class,()->new JdbcTransactionManager(writer));
    context.registerBean("readClock",Clock.class,()->candidateClock);
    org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> closingListener=event->closeStarted.countDown();context.addApplicationListener(closingListener);
    context.register(org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration.class,com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration.class);context.refresh();
    var scheduler=context.getBean("noteExpiryScheduler",org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
    var job=context.getBean("noteExpiryJob",com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration.NoteExpiryJob.class);
    try(Connection blocker=admin()){
     blocker.setAutoCommit(false);try(var s=blocker.createStatement()){s.executeQuery("SELECT member_id FROM pawbridge_community.private_note_mailboxes WHERE note_id='"+note+"' FOR UPDATE").close();}
     try{
      task=scheduler.submit(()->{try{job.expire();}catch(RuntimeException failure){observed.set(failure);throw failure;}finally{released.set(!writer.isClosed()&&!TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.hasResource(writer)&&writer.getHikariPoolMXBean().getActiveConnections()==0);}});
      awaitBlocked("f29-note-send-writer");closing=closer.submit(context::close);
      assertThat(closeStarted.await(2,TimeUnit.SECONDS)).as("Context close begins while the actual owned expiry is waiting on its FK row").isTrue();assertThat(closing.isDone()).isFalse();assertThat(writer.isClosed()).isFalse();
      blocker.rollback();closing.get(20,TimeUnit.SECONDS);task.get(2,TimeUnit.SECONDS);
      assertThat(released.get()).as("Actual TX/connection returns before owned writer close").isTrue();assertThat(writer.isClosed()).isTrue();assertThat(scheduler.getScheduledExecutor().isTerminated()).isTrue();assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();assertThat(scheduler.getScheduledThreadPoolExecutor().getActiveCount()).isZero();
      assertThat(observed.get()).isNull();assertThat(candidateClock.calls.get()).isOne();assertExpiryDelta(before,Set.of(note),Set.of());
     }finally{blocker.rollback();}
    }
   }finally{
    if(closing!=null&&!closing.isDone())closing.cancel(true);
    // An unclosed context may still own the writer; remove the FK blocker before this bounded cleanup.
    if(context.isActive()){var finalClose=closer.submit(context::close);finalClose.get(20,TimeUnit.SECONDS);}
    if(task!=null&&!task.isDone())task.cancel(true);
    finishExpiryRound();
   }
  }finally{closer.shutdownNow();assertThat(closer.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
 }

 private void beginExpiry()throws Exception{
  assertThat(expiryReservedNotes).isEmpty();assertThat(expiryReservedRequests).isEmpty();
  // Select once before owned seed: production Clock and every original F29 FIRST/method remain unchanged.
  Instant safe=FIRST;var state=json(snapshot());
  try{
   for(var n:state.get("private_notes")){Instant at=OffsetDateTime.parse(n.get("expires_at").asText()).toInstant();if(at.isBefore(safe))safe=at;}
   for(var r:state.get("private_note_requests"))if(r.get("note_id").isNull()){Instant at=OffsetDateTime.parse(r.get("created_at").asText()).toInstant().plusSeconds(172800);if(at.isBefore(safe))safe=at;}
   expiryNow=safe.minusSeconds(1);Timestamp.from(expiryNow.minusSeconds(172802));
  }catch(java.time.DateTimeException|ArithmeticException failure){throw new IllegalStateException("Refusing a non-finite or unsupported owned expiry test clock");}
  requireNoForeignExpiryCandidates();
 }
 private UUID expiryId(int n){return UUID.fromString(String.format(Locale.ROOT,"f3000000-0000-0000-0000-%012d",n));}
 private void seedExpiry(List<ExpiryNote> ns,List<ExpiryRequest> rs)throws Exception{
  verifyOwnership();member(CALLER);member(TARGET);
  var newNotes=ns.stream().map(ExpiryNote::id).toList();var newRequests=rs.stream().map(ExpiryRequest::id).toList();
  if(!newNotes.isEmpty())assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id IN ("+uuidList(newNotes)+")")).isZero();
  if(!newRequests.isEmpty())assertThat(scalar("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE request_id IN ("+uuidList(newRequests)+")")).isZero();
  expiryReservedNotes.addAll(newNotes);expiryReservedRequests.addAll(newRequests);
  try(Connection c=admin()){
   c.setAutoCommit(false);
   try(var n=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,reply_to,created_at,expires_at) VALUES(?,?,?,?,?,?,?)");
       var m=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,created_at) VALUES(?,?,?,?)");
       var r=c.prepareStatement("INSERT INTO pawbridge_community.private_note_requests(sender_id,request_id,request_hash,note_id,created_at) VALUES(?,?,?,?,?)")){
    for(var x:ns){n.setObject(1,x.id());n.setLong(2,CALLER);n.setLong(3,TARGET);n.setString(4,EXPIRY_BODY);n.setObject(5,x.replyTo());n.setObject(6,OffsetDateTime.ofInstant(expiryNow.minusSeconds(600000),ZoneOffset.UTC));n.setObject(7,OffsetDateTime.ofInstant(x.expires(),ZoneOffset.UTC));n.addBatch();}
    n.executeBatch();
    for(var x:ns)for(long member:x.bothMailboxes()?new long[]{CALLER,TARGET}:new long[]{CALLER}){m.setLong(1,member);m.setObject(2,x.id());m.setString(3,member==CALLER?"INBOX":"SENT");m.setObject(4,OffsetDateTime.ofInstant(expiryNow.minusSeconds(60),ZoneOffset.UTC));m.addBatch();}m.executeBatch();
    for(var x:rs){r.setLong(1,CALLER);r.setObject(2,x.id());r.setString(3,"F30 owned request");r.setObject(4,x.note());r.setObject(5,OffsetDateTime.ofInstant(x.created(),ZoneOffset.UTC));r.addBatch();}r.executeBatch();c.commit();
   }catch(Exception|Error failure){c.rollback();throw failure;}
  }finally{reobserveExpirySeeds();}
 }
 private void reobserveExpirySeeds()throws Exception{
  verifyOwnership();var state=json(snapshot());
  for(var n:state.get("private_notes"))if(expiryReservedNotes.contains(UUID.fromString(n.get("note_id").asText()))){
   if(n.get("sender_id").asLong()!=CALLER||n.get("recipient_id").asLong()!=TARGET||!EXPIRY_BODY.equals(n.get("body").asText()))throw new IllegalStateException("Refusing an altered reserved expiry note identity");
   notes.add(UUID.fromString(n.get("note_id").asText()));
  }
  for(var r:state.get("private_note_requests"))if(expiryReservedRequests.contains(UUID.fromString(r.get("request_id").asText()))){
   if(r.get("sender_id").asLong()!=CALLER||!"F30 owned request".equals(r.get("request_hash").asText()))throw new IllegalStateException("Refusing an altered reserved expiry request identity");
   requestKeys.add(UUID.fromString(r.get("request_id").asText()));
  }
 }
 private void finishExpiryRound()throws Exception{try{reobserveExpirySeeds();cleanup();reset();}finally{expiryReservedNotes.clear();expiryReservedRequests.clear();}}
 private void requireNoForeignExpiryCandidates()throws SQLException{
  verifyOwnership();String outsideNotes=expiryReservedNotes.isEmpty()?"TRUE":"note_id NOT IN ("+uuidList(expiryReservedNotes)+")";
  String outsideRequests=expiryReservedRequests.isEmpty()?"TRUE":"NOT(sender_id="+CALLER+" AND request_id IN ("+uuidList(expiryReservedRequests)+"))";
  try(Connection c=admin();var s=c.prepareStatement("SELECT (SELECT count(*) FROM pawbridge_community.private_notes WHERE expires_at<=? AND "+outsideNotes+")+(SELECT count(*) FROM pawbridge_community.private_note_requests WHERE note_id IS NULL AND created_at<? AND "+outsideRequests+")")){
   s.setTimestamp(1,Timestamp.from(expiryNow));s.setTimestamp(2,Timestamp.from(expiryNow.minusSeconds(172800)));try(var r=s.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getLong(1)).as("No globally eligible unowned rows may exist before expiry").isZero();}
  }
  if(!expiryReservedNotes.isEmpty()){
   String ids=uuidList(expiryReservedNotes),foreignRequest=expiryReservedRequests.isEmpty()?"TRUE":"NOT(sender_id="+CALLER+" AND request_id IN ("+uuidList(expiryReservedRequests)+"))";
   assertThat(scalar("SELECT (SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE note_id IN ("+ids+") AND member_id NOT IN ("+CALLER+","+TARGET+"))+(SELECT count(*) FROM pawbridge_community.private_notes WHERE reply_to IN ("+ids+") AND note_id NOT IN ("+ids+"))+(SELECT count(*) FROM pawbridge_community.private_note_requests WHERE note_id IN ("+ids+") AND "+foreignRequest+")")).as("No external FK may be changed by owned expiry").isZero();
  }
 }
 private String expiryObservationPhase="unstarted";
 private void expireOwned(boolean source,int expectedCount){
  expiryObservationPhase=source?"original":"candidate";
  try{requireNoForeignExpiryCandidates();}catch(SQLException failure){throw new IllegalStateException("Owned global expiry guard could not be read");}
  sourceClock.reset(expiryNow);candidateClock.reset(expiryNow);
  if(source)original.expire();else{var tx=new TransactionTemplate(new JdbcTransactionManager(writer));tx.setTimeout(15);int count=new com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteExpiry(writeJdbc,tx,candidateClock).expire();if(expectedCount>=0)assertThat(count).isEqualTo(expectedCount);}
  returned();assertThat((source?sourceClock:candidateClock).calls.get()).isOne();assertThat(hints).isEmpty();assertThat(contacts).isEmpty();assertThat(original.contactIds()).isEmpty();
 }
 private void assertExpiryDelta(String before,Set<UUID> deletedNotes,Set<UUID> deletedRequests)throws SQLException{
  assertThat(expiryReservedNotes.containsAll(deletedNotes)).isTrue();assertThat(expiryReservedRequests.containsAll(deletedRequests)).isTrue();
  var expected=(ObjectNode)json(before).deepCopy();
  for(String table:List.of("private_notes","mailboxes","private_note_requests")){
   var rows=(ArrayNode)expected.get(table);
   for(int i=rows.size()-1;i>=0;i--){var row=(ObjectNode)rows.get(i);boolean deletedNote=row.hasNonNull("note_id")&&deletedNotes.contains(UUID.fromString(row.get("note_id").asText()));
    if(table.equals("private_notes")&&deletedNote||table.equals("mailboxes")&&deletedNote||table.equals("private_note_requests")&&deletedRequests.contains(UUID.fromString(row.get("request_id").asText()))){rows.remove(i);continue;}
    if(table.equals("private_notes")&&row.hasNonNull("reply_to")&&deletedNotes.contains(UUID.fromString(row.get("reply_to").asText())))row.putNull("reply_to");
    if(table.equals("private_note_requests")&&deletedNote)row.putNull("note_id");
   }
  }
  var observed=canonical(json(snapshot()));var wanted=canonical(json(expected.toString()));
  var changedTables=new ArrayList<String>();
  for(var keys=wanted.fieldNames();keys.hasNext();){String table=keys.next();var expectedRows=wanted.get(table);var actualRows=observed.get(table);
   if(!Objects.equals(expectedRows,actualRows))changedTables.add(table+":expectedRows="+expectedRows.size()+",actualRows="+(actualRows==null?-1:actualRows.size()));
  }
  assertThat(observed.equals(wanted)).as("Only the explicit owned note/mailbox/request/reply delta may change among all eighteen tables; phase=%s; changedTables=%s",expiryObservationPhase,changedTables).isTrue();
 }
 private AutoCloseable expiryPrivileges()throws SQLException{
  verifyOwnership();requireExpiryPrivileges(false);
  try{execute("BEGIN; GRANT DELETE,UPDATE(request_id) ON pawbridge_community.private_note_requests TO pawbridge_f3_writer; COMMIT;");requireExpiryPrivileges(true);}
  catch(SQLException|RuntimeException|Error failure){restoreExpiryPrivileges();throw failure;}
  return this::restoreExpiryPrivileges;
 }
 private void restoreExpiryPrivileges()throws SQLException{
  // Existing receipt/marker checks intentionally avoid strict old ACL while the two test-only rights are granted.
  verifyOwnership();execute("BEGIN; REVOKE DELETE,UPDATE(request_id) ON pawbridge_community.private_note_requests FROM pawbridge_f3_writer; COMMIT;");requireExpiryPrivileges(false);requireWriterPrivileges();
 }
 private void requireExpiryPrivileges(boolean enabled)throws SQLException{
  assertThat(scalar("SELECT has_table_privilege('pawbridge_f3_writer','pawbridge_community.private_note_requests','DELETE')::int")).isEqualTo(enabled?1:0);
  assertThat(scalar("SELECT (count(*)=5 AND bool_and(has_column_privilege('pawbridge_f3_writer','pawbridge_community.private_note_requests',column_name,'UPDATE')=(column_name='request_id' AND "+enabled+")) AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.private_note_requests','UPDATE'))::int FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='private_note_requests'")).isOne();
 }
 private void requireOwnedExpiryCommitFailure(Throwable failure){
  var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var queue=new ArrayDeque<Throwable>();queue.add(failure);boolean found=false;
  while(!queue.isEmpty()&&seen.size()<32){var value=queue.removeFirst();if(!seen.add(value))continue;if(value instanceof PSQLException p&&"23514".equals(p.getSQLState())&&p.getServerErrorMessage()!=null&&"f30_owned_expiry_rejection".equals(p.getServerErrorMessage().getConstraint()))found=true;if(value.getCause()!=null)queue.add(value.getCause());if(value instanceof SQLException s&&s.getNextException()!=null)queue.add(s.getNextException());}
  assertThat(found).as("The actual owned deferred constraint, not an arbitrary commit exception, rejected both managers").isTrue();
 }
}
