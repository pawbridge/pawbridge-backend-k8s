package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.NoteNotifications;
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import com.pawbridge.backend.community.internal.note.application.NoteNotificationsRejectedException;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteNotificationReader;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F21LegacyNotificationsService;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import feign.FeignException;
import feign.Request;
import feign.Response;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Original n.* uses the owned reference connection; candidate reads only its nine allowed columns. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteNotificationsCompatibilityTest {
    private static final long BASE = 9_975_100L;
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Set<Long> ownedUsers = new LinkedHashSet<>(), ownedMembers = new LinkedHashSet<>(), ownedPending = new LinkedHashSet<>();
    private final Set<UUID> ownedNotes = new LinkedHashSet<>();
    private final Set<Mailbox> ownedMailboxes = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc, reference;
    private ContactMemberQuery contacts;
    private ContactMembersQuery batch;
    private NoteNotificationReader notes;
    private ReadNoteNotifications candidate;
    private F21LegacyNotificationsService original;
    private UserServiceClient legacyClient;

    @BeforeAll void openOnlyOwnedFixtureAndOneRestrictedReader() throws Exception {
        assertThat(System.getProperty("user.timezone")).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        var config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f21-notifications-reader");
        reader = new HikariDataSource(config); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        reference = new JdbcTemplate(new AbstractDataSource() {
            public Connection getConnection() throws SQLException { return admin(); }
            public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Only owned reference connections"); }
        });
        reference.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        var single = new JdbcContactMemberQuery(jdbc, tx);
        var multiple = new JdbcContactMembersQuery(jdbc, tx);
        var pages = new JdbcNoteNotificationReader(jdbc, tx);
        contacts = id -> { returned(); try { return single.get(id); } finally { returned(); } };
        batch = ids -> { returned(); try { return multiple.getAll(ids); } finally { returned(); } };
        notes = new NoteNotificationReader() {
            public List<Row> read(long id, UUID cursor, Instant now) { returned(); try { return pages.read(id,cursor,now); } finally { returned(); } }
            public long unread(long id, Instant now) { returned(); try { return pages.unread(id,now); } finally { returned(); } }
        };
        candidate = new ReadNoteNotifications(contacts, notes, batch, CLOCK);
        var legacyContacts = new com.pawbridge.userservice.contact.ContactMemberQuery(jdbc);
        legacyClient = mock(UserServiceClient.class);
        when(legacyClient.getContactMember(anyLong())).thenAnswer(call -> {
            returned();
            try { return projection(legacyContacts.get(call.getArgument(0,Long.class))); }
            catch (RuntimeException failure) { throw oldTransportFailure(); }
            finally { returned(); }
        });
        when(legacyClient.getContactMembers(anyList())).thenAnswer(call -> {
            returned();
            try { return legacyContacts.getAll(call.getArgument(0)).stream().map(this::projection).toList(); }
            catch (RuntimeException failure) { throw oldTransportFailure(); }
            finally { returned(); }
        });
        original = new F21LegacyNotificationsService(new PrivateNoteRepository(reference), legacyClient, CLOCK);
        returned();
    }
    private PrivateNoteModels.ContactMember projection(com.pawbridge.userservice.contact.ContactMember value) {
        return new PrivateNoteModels.ContactMember(value.userId(),value.nickname(),value.active(),value.deletionPending());
    }
    private RuntimeException oldTransportFailure() {
        var request = Request.create(Request.HttpMethod.GET,"https://fixture.invalid/contact",Map.of(),new byte[0],StandardCharsets.UTF_8,null);
        return FeignException.errorStatus("synthetic",Response.builder().status(500).reason("synthetic").request(request).headers(Map.of()).build());
    }

    @AfterEach void removeOnlySuccessfullyInsertedKeys() throws Exception {
        if (owner != null) {
            try (Connection c=admin(); var s=c.prepareStatement("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id=? AND note_id=?")) {
                for (Mailbox m:ownedMailboxes) { s.setLong(1,m.member()); s.setObject(2,m.note()); s.executeUpdate(); }
            }
            try (Connection c=admin(); var s=c.prepareStatement("DELETE FROM pawbridge_community.private_notes WHERE note_id=?")) {
                for (UUID id:ownedNotes) { s.setObject(1,id); s.executeUpdate(); }
            }
            deleteIds("pawbridge_community.private_note_members","member_id",ownedMembers);
            deleteIds("pawbridge_user.contact_deletions","user_id",ownedPending);
            deleteIds("pawbridge_user.users","user_id",ownedUsers);
        }
        ownedMailboxes.clear(); ownedNotes.clear(); ownedPending.clear(); ownedMembers.clear(); ownedUsers.clear();
        if (reader!=null) returned();
    }
    @AfterAll void closeOnlyOwnPool() { if(reader!=null) reader.close(); }

    @Test void givenTwentyThreeLiveInboxRowsAndDifferentMailboxNoteTimes__whenPaging__thenActualSourceAndCandidateKeepTwentyPlusSentinelPostgresUuidOrderAndGlobalUnread() throws Exception {
        people();
        for(int i=1;i<=23;i++) {
            Long sender = switch(i%4) { case 0 -> null; case 1 -> BASE+2; case 2 -> BASE+3; default -> BASE+4; };
            note(i,sender,BASE+1,"INBOX",i==1?NOW.plusSeconds(1):NOW,NOW.minusSeconds(i*30),NOW.plusSeconds(60),i%3==0?NOW:null);
        }
        note(24,BASE+2,BASE+1,"INBOX",NOW.plusSeconds(5),NOW,NOW,null);
        note(25,BASE+2,BASE+1,"INBOX",NOW.plusSeconds(5),NOW,NOW.minusSeconds(1),null);
        note(26,BASE+1,BASE+1,"SENT",NOW.plusSeconds(5),NOW,NOW.plusSeconds(60),null);
        note(27,BASE+2,BASE+10,"INBOX",NOW.plusSeconds(5),NOW,NOW.plusSeconds(60),null);
        String before=snapshot(); var first=same(null);
        List<UUID> expected=new ArrayList<>(); expected.add(uuid(1));
        for(int i=22;i>=2;i-=2) expected.add(uuid(i));
        for(int i=23;i>=3;i-=2) expected.add(uuid(i));
        assertThat(first.content()).hasSize(20); assertThat(first.unreadCount()).isEqualTo(16);
        assertThat(first.content()).extracting(NoteNotifications.Notification::noteId).containsExactlyElementsOf(expected.subList(0,20));
        assertThat(first.nextCursor()).isEqualTo(uuid(9));
        assertThat(first.content().getFirst().createdAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(first.content().getFirst().actorNickname()).isEqualTo("  F21raw"+BASE+"  ");
        var tail=same(first.nextCursor()); assertThat(tail.content()).extracting(NoteNotifications.Notification::noteId).containsExactlyElementsOf(expected.subList(20,23));
        assertThat(tail.nextCursor()).isNull(); assertThat(tail.unreadCount()).isEqualTo(16);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"unknown","foreign","sent-only","expired-own"})
    void givenInboxScopedCursorKinds__whenReading__thenUnknownForeignAndSentAreEmptyButExpiredOwnRemainsAnAnchorAndUnreadIsIndependent(String kind) throws Exception {
        people();
        note(1,BASE+2,BASE+1,"INBOX",NOW.plusSeconds(1),NOW.minusSeconds(10),NOW.plusSeconds(60),null);
        note(2,BASE+2,BASE+1,"INBOX",NOW,NOW.minusSeconds(20),NOW.plusSeconds(60),null);
        note(3,BASE+2,BASE+1,"INBOX",NOW.plusSeconds(2),NOW,NOW.minusSeconds(1),null);
        note(4,BASE+2,BASE+10,"INBOX",NOW.plusSeconds(5),NOW,NOW.plusSeconds(60),null);
        note(5,BASE+1,BASE+1,"SENT",NOW.plusSeconds(5),NOW,NOW.plusSeconds(60),null);
        UUID cursor=uuid(switch(kind){case "foreign"->4;case "sent-only"->5;case "expired-own"->3;default->99;});
        String before=snapshot(); var result=same(cursor);
        assertThat(result.unreadCount()).isEqualTo(2); assertThat(result.nextCursor()).isNull();
        if(kind.equals("expired-own")) assertThat(result.content()).extracting(NoteNotifications.Notification::noteId).containsExactly(uuid(1),uuid(2));
        else assertThat(result.content()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"missing","pending"})
    void givenInactiveCallerAndRevokedExpiryColumn__whenReading__thenInactive403PrecedesNotificationSql(String kind) throws Exception {
        if(kind.equals("pending")){user(BASE+1,"F21caller"+BASE);pending(BASE+1);}
        String before=snapshot();
        execute("REVOKE SELECT(expires_at) ON pawbridge_community.private_notes FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(()->original.notifications(BASE+1,null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403)); returned();
            assertThatThrownBy(()->candidate.read(BASE+1,null)).isInstanceOfSatisfying(NoteNotificationsRejectedException.class,e->assertThat(e.reason()).isEqualTo(NoteNotificationsRejectedException.Reason.INACTIVE)); returned();
            assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT(expires_at) ON pawbridge_community.private_notes TO pawbridge_f2_reader"); }
    }

    @ParameterizedTest @ValueSource(strings={"expires_at","read_at"})
    void givenMinimumReaderAndRequiredColumnRevoked__whenCandidateReads__thenReferenceNeedsNoWiderCandidateAclAndSqlFailureRecovers(String column) throws Exception {
        people(); note(1,BASE+2,BASE+1,"INBOX",NOW,NOW.minusSeconds(1),NOW.plusSeconds(60),null); String before=snapshot();
        assertThatThrownBy(()->jdbc.queryForObject("SELECT body FROM pawbridge_community.private_notes LIMIT 1",String.class)).isInstanceOf(DataAccessException.class); returned();
        String table=column.equals("expires_at")?"private_notes":"private_note_mailboxes";
        execute("REVOKE SELECT("+column+") ON pawbridge_community."+table+" FROM pawbridge_f2_reader");
        try {
            assertThat(original.notifications(BASE+1,null).content()).hasSize(1); returned();
            assertThatThrownBy(()->candidate.read(BASE+1,null)).isInstanceOf(DataAccessException.class); returned();
            assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT("+column+") ON pawbridge_community."+table+" TO pawbridge_f2_reader"); }
        same(null); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void givenUserNicknameRevokedAfterListConnectionReturns__whenBatchRuns__thenBothMemberBoundariesFailWithoutPartialNotificationsAndRecover() throws Exception {
        people(); note(1,BASE+2,BASE+1,"INBOX",NOW,NOW,NOW.plusSeconds(60),null); String before=snapshot();
        var hit=new AtomicBoolean();
        var hookedOriginal=new F21LegacyNotificationsService(new PrivateNoteRepository(reference){
            @Override public List<PrivateNoteModels.Note> notifications(long id,UUID cursor,Instant now){var rows=super.notifications(id,cursor,now);returned();revoke("pawbridge_user.users","nickname");hit.set(true);return rows;}
        },legacyClient,CLOCK);
        try { assertThatThrownBy(()->hookedOriginal.notifications(BASE+1,null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));returned();assertThat(hit).isTrue(); }
        finally { execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        hit.set(false);
        var hookedReader=new NoteNotificationReader(){
            public List<Row> read(long id,UUID cursor,Instant now){var rows=notes.read(id,cursor,now);returned();revoke("pawbridge_user.users","nickname");hit.set(true);return rows;}
            public long unread(long id,Instant now){return notes.unread(id,now);}
        };
        try { assertThatThrownBy(()->new ReadNoteNotifications(contacts,hookedReader,batch,CLOCK).read(BASE+1,null)).isInstanceOf(ContactMemberUnavailableException.class);returned();assertThat(hit).isTrue(); }
        finally { execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        same(null); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void givenReadAtRevokedOnlyAfterTheCompletedBatch__whenUnreadRuns__thenCandidateUnreadFailureIsNotMisreportedAsListOrMemberFailureAndRecovers() throws Exception {
        people(); note(1,BASE+2,BASE+1,"INBOX",NOW,NOW,NOW.plusSeconds(60),null); String before=snapshot();
        assertThat(original.notifications(BASE+1,null).unreadCount()).isEqualTo(1); returned();
        var hit=new AtomicBoolean();
        ContactMembersQuery hookedBatch=ids->{var result=batch.getAll(ids);returned();revoke("pawbridge_community.private_note_mailboxes","read_at");hit.set(true);return result;};
        try { assertThatThrownBy(()->new ReadNoteNotifications(contacts,notes,hookedBatch,CLOCK).read(BASE+1,null)).isInstanceOf(DataAccessException.class);returned();assertThat(hit).isTrue();assertThat(snapshot()).isEqualTo(before); }
        finally { execute("GRANT SELECT(read_at) ON pawbridge_community.private_note_mailboxes TO pawbridge_f2_reader"); }
        same(null); assertThat(snapshot()).isEqualTo(before);
    }
    private void revoke(String table,String column){try{execute("REVOKE SELECT("+column+") ON "+table+" FROM pawbridge_f2_reader");}catch(SQLException failure){throw new IllegalStateException(failure);}}

    @Test void givenMailboxAndNoteTstzMicroseconds__whenReadingAndSerializing__thenUseNoteInstantAndStrictExpiryWithoutZoneShift() throws Exception {
        people();
        note(1,BASE+2,BASE+1,"INBOX",NOW.plusSeconds(10),NOW.minusSeconds(1),NOW.plusSeconds(1),NOW);
        note(2,null,BASE+1,"INBOX",NOW.plusSeconds(20),NOW,NOW,null);
        String before=snapshot(); var result=same(null);
        assertThat(result.content()).hasSize(1); assertThat(result.content().getFirst().createdAt()).isEqualTo(NOW.minusSeconds(1)); assertThat(result.content().getFirst().read()).isTrue();assertThat(result.unreadCount()).isZero();
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=context.getBean(ObjectMapper.class); var json=mapper.valueToTree(result);
            assertThat(json.path("content").get(0).path("createdAt").asText()).isEqualTo("2030-12-31T23:59:59.123456Z");
            assertThat(json.path("content").get(0).size()).isEqualTo(7);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test @Order(Integer.MAX_VALUE)
    void givenOriginalIssuerAndOwnedFile__whenPreparingOnlyTheUsedJarPrincipal__thenPreservePreviousKeysAndPermissions() throws Exception {
        verifyOwnership(); String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue(); assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper(); Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(file),new TypeReference<Map<String,String>>(){})); var oldKeys=Set.copyOf(tokens.keySet());
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var user=User.builder().userId(9_975_001L).email("f21-token@fixture.invalid").name("F21 synthetic").nickname("F21token").provider("GOOGLE").role(Role.ROLE_USER).build();
        String token=new JwtProvider(secret,600_000,600_000).createAccessToken(user);
        assertThat(new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).authenticate("Bearer "+token)).isEqualTo(9_975_001L);
        tokens.put("F21_CALLER",token); assertThat(tokens.keySet()).containsAll(oldKeys); Files.writeString(file,mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); returned();
    }
    private NoteNotifications same(UUID cursor){
        var expected=original.notifications(BASE+1,cursor);returned();var actual=candidate.read(BASE+1,cursor);returned();
        assertThat(actual.nextCursor()).isEqualTo(expected.nextCursor()); assertThat(actual.unreadCount()).isEqualTo(expected.unreadCount());assertThat(actual.content()).hasSize(expected.content().size());
        for(int i=0;i<expected.content().size();i++){var a=actual.content().get(i);var e=expected.content().get(i);assertThat(a.noteId()).isEqualTo(e.noteId());assertThat(a.kind()).isEqualTo(e.kind());assertThat(a.actorId()).isEqualTo(e.actorId());assertThat(a.actorNickname()).isEqualTo(e.actorNickname());assertThat(a.createdAt()).isEqualTo(e.createdAt());assertThat(a.read()).isEqualTo(e.read());assertThat(a.href()).isEqualTo(e.href());}
        return actual;
    }
    private void people() throws SQLException { user(BASE+1,"F21caller"+BASE);user(BASE+2,"  F21raw"+BASE+"  ");user(BASE+3,"F21pending"+BASE);pending(BASE+3); }
    private UUID uuid(int serial){return new UUID(serial%2==0?Long.MIN_VALUE:0L,serial);}
    private void member(long id) throws SQLException {
        if(!ownedMembers.contains(id))try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(?)")){s.setLong(1,id);s.executeUpdate();ownedMembers.add(id);}
    }
    private void note(int serial,Long sender,long mailboxOwner,String direction,Instant mailboxTime,Instant noteTime,Instant expiresAt,Instant readAt) throws SQLException {
        UUID id=uuid(serial); long recipient=direction.equals("SENT")?BASE+2:mailboxOwner;
        if(sender!=null)member(sender);member(recipient);member(mailboxOwner);
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES(?,?,?,'F21 synthetic only',?,?)")){
            s.setObject(1,id);s.setObject(2,sender);s.setLong(3,recipient);s.setObject(4,java.time.OffsetDateTime.ofInstant(noteTime,ZoneOffset.UTC));s.setObject(5,java.time.OffsetDateTime.ofInstant(expiresAt,ZoneOffset.UTC));s.executeUpdate();ownedNotes.add(id);
        }
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES(?,?,?,?,?,?)")){
            s.setLong(1,mailboxOwner);s.setObject(2,id);s.setString(3,direction);s.setObject(4,readAt==null?null:java.time.OffsetDateTime.ofInstant(readAt,ZoneOffset.UTC));s.setBoolean(5,serial%2==0);s.setObject(6,java.time.OffsetDateTime.ofInstant(mailboxTime,ZoneOffset.UTC));s.executeUpdate();ownedMailboxes.add(new Mailbox(mailboxOwner,id));
        }
    }
    private void user(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f21-" + id + "@fixture.invalid"); s.setString(3, "F21 synthetic");
            s.setString(4, nickname); s.setString(5, "GOOGLE"); s.setString(6, "ROLE_USER"); s.executeUpdate(); ownedUsers.add(id);
        }
    }
    private void pending(long id) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(?)")) {
            s.setLong(1, id); s.executeUpdate(); ownedPending.add(id);
        }
    }
    private void deleteIds(String table, String column, Set<Long> ids) throws SQLException {
        if (!Set.of("pawbridge_user.contact_deletions.user_id", "pawbridge_community.private_note_members.member_id", "pawbridge_user.users.user_id").contains(table + "." + column))
            throw new IllegalArgumentException("Only owned F21 key cleanup is supported");
        try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM " + table + " WHERE " + column + "=?")) {
            for (long id : ids) { s.setLong(1, id); s.executeUpdate(); }
        }
    }
    private Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT 16,value,owner FROM pawbridge_f16_fixture.marker UNION ALL SELECT 17,value,owner FROM pawbridge_f17_fixture.marker ORDER BY 1")) {
            String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{16, 17}) {
                assertThat(F3PostgresqlFixture.required("F" + stage + "_CI_OWNER")).isEqualTo(run);
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(stage);
                assertThat(rows.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1"); assertThat(rows.getString(3)).isEqualTo(run);
            }
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { c.close(); throw failure; }
        return c;
    }
    private void verifyOwnership() { try (Connection ignored = admin()) { } catch (SQLException e) { throw new IllegalStateException(e); } }
    private void execute(String sql) throws SQLException { try (Connection c = admin(); var s = c.createStatement()) { s.execute(sql); } }
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
    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne(); assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private record Mailbox(long member,UUID note) { }
}
