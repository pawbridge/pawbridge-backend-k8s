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
import com.pawbridge.backend.community.internal.note.application.PrivateNoteView;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteRejectedException;
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteReader;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F22LegacyPrivateNoteService;
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
import java.util.Optional;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockReader;
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

/** Private draft: owned reference reads original n.*, candidate uses exactly allowed detail columns. Unexecuted. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlPrivateNoteReadCompatibilityTest {
    private static final long BASE=9_976_100L;
    private static final Instant NOW=Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private final Set<Long> ownedUsers=new LinkedHashSet<>(),ownedMembers=new LinkedHashSet<>(),ownedPending=new LinkedHashSet<>();
    private final Set<UUID> ownedNotes=new LinkedHashSet<>();
    private final Set<Mailbox> ownedMailboxes=new LinkedHashSet<>();
    private final Set<Block> ownedBlocks=new LinkedHashSet<>();
    private final List<String> phases=new ArrayList<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc,reference;
    private ContactMemberQuery contacts;
    private PrivateNoteReader notes;
    private NoteBlockReader blocks;
    private ReadPrivateNote candidate;
    private F22LegacyPrivateNoteService original;
    @BeforeAll void openOnlyOwnedFixtureAndRestrictedSingleConnection() throws Exception {
        assertThat(System.getProperty("user.timezone")).isIn("UTC","Asia/Seoul");
        owner=F3PostgresqlFixture.open();verifyOwnership();
        var config=new HikariConfig();config.setJdbcUrl(owner.reads().jdbcUrl());config.setUsername(owner.reads().applicationUser());config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true);config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2_000);config.setValidationTimeout(1_000);config.setPoolName("f22-private-note-reader");
        reader=new HikariDataSource(config);jdbc=new JdbcTemplate(reader);jdbc.setQueryTimeout(3);
        reference=new JdbcTemplate(new AbstractDataSource(){
            public Connection getConnection() throws SQLException{return admin();}
            public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Only owned reference connection");}
        });reference.setQueryTimeout(3);
        var manager=new JdbcTransactionManager(reader);manager.setEnforceReadOnly(true);var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setTimeout(3);
        var single=new JdbcContactMemberQuery(jdbc,tx);var detail=new JdbcPrivateNoteReader(jdbc,tx);var block=new JdbcNoteBlockReader(jdbc,tx);
        contacts=id->{returned();phases.add("member");try{return single.get(id);}finally{returned();}};
        notes=(id,note,now)->{returned();phases.add("note");try{return detail.find(id,note,now);}finally{returned();}};
        blocks=(first,second)->{returned();phases.add("block");try{return block.blocked(first,second);}finally{returned();}};
        candidate=new ReadPrivateNote(contacts,notes,blocks,CLOCK);
        var oldContacts=new com.pawbridge.userservice.contact.ContactMemberQuery(jdbc);var client=mock(UserServiceClient.class);
        when(client.getContactMember(anyLong())).thenAnswer(call->{returned();try{var value=oldContacts.get(call.getArgument(0,Long.class));return new PrivateNoteModels.ContactMember(value.userId(),value.nickname(),value.active(),value.deletionPending());}catch(RuntimeException failure){throw oldTransportFailure();}finally{returned();}});
        original=new F22LegacyPrivateNoteService(new PrivateNoteRepository(reference),client,CLOCK);returned();
    }
    private RuntimeException oldTransportFailure(){var request=Request.create(Request.HttpMethod.GET,"https://fixture.invalid/contact",Map.of(),new byte[0],StandardCharsets.UTF_8,null);return FeignException.errorStatus("synthetic",Response.builder().status(500).reason("synthetic").request(request).headers(Map.of()).build());}
    @AfterEach void removeOnlySuccessfulOwnedKeys()throws Exception{
        if(owner!=null){
            try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id=? AND blocked_id=?")){for(Block b:ownedBlocks){s.setLong(1,b.first());s.setLong(2,b.second());s.executeUpdate();}}
            try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id=? AND note_id=?")){for(Mailbox m:ownedMailboxes){s.setLong(1,m.member());s.setObject(2,m.note());s.executeUpdate();}}
            try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.private_notes WHERE note_id=?")){for(UUID id:ownedNotes){s.setObject(1,id);s.executeUpdate();}}
            deleteIds("pawbridge_community.private_note_members","member_id",ownedMembers);deleteIds("pawbridge_user.contact_deletions","user_id",ownedPending);deleteIds("pawbridge_user.users","user_id",ownedUsers);
        }
        ownedBlocks.clear();ownedMailboxes.clear();ownedNotes.clear();ownedPending.clear();ownedMembers.clear();ownedUsers.clear();phases.clear();if(reader!=null)returned();
    }
    @AfterAll void closeOnlyOwnPool(){if(reader!=null)reader.close();}
    @ParameterizedTest @ValueSource(strings={"INBOX","SENT"})
    void givenOwnInboxOrSentAndRawPrivateBody__whenReading__thenOriginalAndCandidatePreserveTenFieldsAndSeparateMailboxStateWithoutWrites(String direction)throws Exception{
        people("  raw nickname  ");UUID id=note(1,direction.equals("INBOX")?BASE+2:BASE+1,direction.equals("INBOX")?BASE+1:BASE+2,BASE+1,direction,NOW.minusSeconds(1),NOW.plusSeconds(60),NOW,true,"POST",23L);
        String before=snapshot();var result=same(id);assertThat(result.body()).isEqualTo("  F22 private synthetic body\nsecond line  ");assertThat(result.direction()).isEqualTo(direction);assertThat(result.favorite()).isTrue();assertThat(result.readAt()).isEqualTo(NOW);assertThat(result.counterpartId()).isEqualTo(BASE+2);assertThat(result.counterpartNickname()).isEqualTo("  raw nickname  ");assertThat(result.contextHref()).isEqualTo("/community/23");assertThat(result.canReply()).isEqualTo(direction.equals("INBOX"));
        assertThat(phases).containsExactlyElementsOf(direction.equals("INBOX")?List.of("member","note","member","block"):List.of("member","note","member"));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"null","missing","pending","blank","space"})
    void givenNullableMissingPendingOrRawActiveCounterpart__whenReading__thenKeepTheOwnedBodyButOnlyActiveInboxCanReply(String kind)throws Exception{
        people(kind.equals("blank")?"":kind.equals("space")?"   ":"raw");Long sender=switch(kind){case "null"->null;case "missing"->BASE+4;case "pending"->BASE+3;default->BASE+2;};
        UUID id=note(2,sender,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);String before=snapshot();var result=same(id);boolean active=kind.equals("blank")||kind.equals("space");
        assertThat(result.counterpartId()).isEqualTo(active?BASE+2:null);assertThat(result.counterpartNickname()).isEqualTo(active?(kind.equals("blank")?"":"   "):"\uD0C8\uD1F4\uD55C \uD68C\uC6D0");assertThat(result.canReply()).isEqualTo(active);assertThat(result.contextHref()).isNull();assertThat(result.readAt()).isNull();assertThat(result.favorite()).isFalse();assertThat(phases.stream().filter("block"::equals).count()).isEqualTo(active?1:0);assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"absent","foreign","mailbox-deleted","note-deleted","equal-expiry","past-expiry"})
    void givenMissingForeignDeletedOrExpiredOwnedNote__whenReading__thenBothReturnNotFoundBeforeCounterpartAndBlock(String kind)throws Exception{
        people("raw");UUID id=uuid(3);
        if(!kind.equals("absent")){long mailbox=kind.equals("foreign")?BASE+5:BASE+1;id=note(3,BASE+2,BASE+1,mailbox,"INBOX",NOW,kind.equals("equal-expiry")?NOW:kind.equals("past-expiry")?NOW.minusNanos(1000):NOW.plusSeconds(60),null,false,null,null);}
        if(kind.equals("mailbox-deleted"))execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id="+(BASE+1)+" AND note_id='"+id+"'");
        if(kind.equals("note-deleted"))execute("DELETE FROM pawbridge_community.private_notes WHERE note_id='"+id+"'");
        UUID target=id;String before=snapshot();assertThatThrownBy(()->original.get(BASE+1,target)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));returned();phases.clear();
        assertThatThrownBy(()->candidate.read(BASE+1,target)).isInstanceOfSatisfying(PrivateNoteRejectedException.class,e->assertThat(e.reason()).isEqualTo(PrivateNoteRejectedException.Reason.NOT_FOUND));returned();assertThat(phases).containsExactly("member","note");assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void givenEitherDirectionBlock__whenReadingOwnInbox__thenKeepBody200ButDisableReplyWithoutWrites(boolean outgoing)throws Exception{
        people("raw");UUID id=note(4,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,"REPORT",42L);block(outgoing?BASE+1:BASE+2,outgoing?BASE+2:BASE+1);String before=snapshot();var result=same(id);assertThat(result.canReply()).isFalse();assertThat(result.contextHref()).isEqualTo("/reports/42");assertThat(result.body()).contains("F22 private synthetic");assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"recipient_id","body","context_type","context_id","favorite"})
    void givenOneRequiredDetailColumnRevoked__whenCandidateReads__thenOwnerReferenceStillWorksAndMinimumReaderFailsUntilRestored(String column)throws Exception{
        people("raw");UUID id=note(5,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,"POST",1L);String before=snapshot();String table="pawbridge_community."+(column.equals("favorite")?"private_note_mailboxes":"private_notes");revoke(table,column);
        try{assertThat(original.get(BASE+1,id).body()).contains("F22 private synthetic");returned();phases.clear();assertThatThrownBy(()->candidate.read(BASE+1,id)).isInstanceOf(DataAccessException.class);returned();assertThat(phases).containsExactly("member","note");assertThat(snapshot()).isEqualTo(before);}finally{execute("GRANT SELECT("+column+") ON "+table+" TO pawbridge_f2_reader");}
        same(id);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void givenNicknameRevokedAfterOwnedNoteConnectionReturns__whenCounterpartRuns__thenMemberFailureIsNotHiddenAsWithdrawnAndRecoveryWorks()throws Exception{
        people("raw");UUID id=note(6,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);String before=snapshot();same(id);var hit=new AtomicBoolean();
        PrivateNoteReader hooked=(current,note,now)->{var found=notes.find(current,note,now);returned();revoke("pawbridge_user.users","nickname");hit.set(true);return found;};phases.clear();
        try{assertThatThrownBy(()->new ReadPrivateNote(contacts,hooked,blocks,CLOCK).read(BASE+1,id)).isInstanceOf(ContactMemberUnavailableException.class);returned();assertThat(hit).isTrue();assertThat(phases).containsExactly("member","note","member");assertThat(snapshot()).isEqualTo(before);}finally{execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");}
        same(id);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void givenBlockSelectRevokedOnlyAfterActiveCounterpartReturns__whenCanReplyRuns__thenOnlyBlockFailsAndGrantRestorationRecovers()throws Exception{
        people("raw");UUID id=note(7,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);String before=snapshot();same(id);var calls=new java.util.concurrent.atomic.AtomicInteger();
        ContactMemberQuery hooked=current->{var member=contacts.get(current);if(calls.incrementAndGet()==2)revoke("pawbridge_community.private_note_blocks","member_id");return member;};phases.clear();
        try{assertThatThrownBy(()->new ReadPrivateNote(hooked,notes,blocks,CLOCK).read(BASE+1,id)).isInstanceOf(DataAccessException.class);returned();assertThat(calls).hasValue(2);assertThat(phases).containsExactly("member","note","member","block");assertThat(snapshot()).isEqualTo(before);}finally{execute("GRANT SELECT(member_id) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader");}
        same(id);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void givenDifferentMailboxNoteAndReadTstzMicroseconds__whenReadingAndSerializing__thenPreserveNoteAndReadInstantsWithoutZoneShift()throws Exception{
        people("raw");UUID id=note(8,BASE+2,BASE+1,BASE+1,"INBOX",NOW.minusSeconds(1),NOW.plusSeconds(1),NOW,false,null,null);String before=snapshot();var result=same(id);assertThat(result.createdAt()).isEqualTo(NOW.minusSeconds(1));assertThat(result.readAt()).isEqualTo(NOW);
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){var json=context.getBean(ObjectMapper.class).valueToTree(result);assertThat(json.size()).isEqualTo(10);assertThat(json.path("createdAt").asText()).isEqualTo("2030-12-31T23:59:59.123456Z");assertThat(json.path("readAt").asText()).isEqualTo("2031-01-01T00:00:00.123456Z");}assertThat(snapshot()).isEqualTo(before);
    }
    @Test @Order(Integer.MAX_VALUE)
    void givenOriginalIssuerAndOwnedFile__whenPreparingOnlyTheUsedJarPrincipal__thenPreservePreviousKeysAndPermissions() throws Exception {
        verifyOwnership(); String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue(); assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper(); Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(file),new TypeReference<Map<String,String>>(){})); var oldKeys=Set.copyOf(tokens.keySet());
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var user=User.builder().userId(9_976_001L).email("f22-token@fixture.invalid").name("F22 synthetic").nickname("F22token").provider("GOOGLE").role(Role.ROLE_USER).build();
        String token=new JwtProvider(secret,600_000,600_000).createAccessToken(user);
        assertThat(new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).authenticate("Bearer "+token)).isEqualTo(9_976_001L);
        tokens.put("F22_CALLER",token); assertThat(tokens.keySet()).containsAll(oldKeys); Files.writeString(file,mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); returned();
    }
    private PrivateNoteView same(UUID id){
        var expected=original.get(BASE+1,id);returned();phases.clear();var actual=candidate.read(BASE+1,id);returned();
        assertThat(actual.noteId()).isEqualTo(expected.noteId());assertThat(actual.body()).isEqualTo(expected.body());assertThat(actual.direction()).isEqualTo(expected.direction());assertThat(actual.createdAt()).isEqualTo(expected.createdAt());assertThat(actual.readAt()).isEqualTo(expected.readAt());assertThat(actual.favorite()).isEqualTo(expected.favorite());assertThat(actual.counterpartId()).isEqualTo(expected.counterpartId());assertThat(actual.counterpartNickname()).isEqualTo(expected.counterpartNickname());assertThat(actual.canReply()).isEqualTo(expected.canReply());assertThat(actual.contextHref()).isEqualTo(expected.contextHref());return actual;
    }
    private void people(String nickname)throws SQLException{user(BASE+1,"F22caller");user(BASE+2,nickname);user(BASE+3,"F22pending");pending(BASE+3);}
    private UUID uuid(int serial){return new UUID(22L,serial);}
    private void member(long id)throws SQLException{if(!ownedMembers.contains(id))try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(?)")){s.setLong(1,id);s.executeUpdate();ownedMembers.add(id);}}
    private UUID note(int serial,Long sender,Long recipient,long mailboxOwner,String direction,Instant noteTime,Instant expires,Instant readAt,boolean favorite,String contextType,Long contextId)throws SQLException{
        UUID id=uuid(serial);if(sender!=null)member(sender);if(recipient!=null)member(recipient);member(mailboxOwner);
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,context_type,context_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)")){s.setObject(1,id);s.setObject(2,sender);s.setObject(3,recipient);s.setString(4,"  F22 private synthetic body\nsecond line  ");s.setString(5,contextType);s.setObject(6,contextId);s.setObject(7,java.time.OffsetDateTime.ofInstant(noteTime,ZoneOffset.UTC));s.setObject(8,java.time.OffsetDateTime.ofInstant(expires,ZoneOffset.UTC));s.executeUpdate();ownedNotes.add(id);}
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES(?,?,?,?,?,?)")){s.setLong(1,mailboxOwner);s.setObject(2,id);s.setString(3,direction);s.setObject(4,readAt==null?null:java.time.OffsetDateTime.ofInstant(readAt,ZoneOffset.UTC));s.setBoolean(5,favorite);s.setObject(6,java.time.OffsetDateTime.ofInstant(NOW.plusSeconds(40),ZoneOffset.UTC));s.executeUpdate();ownedMailboxes.add(new Mailbox(mailboxOwner,id));}return id;
    }
    private void block(long first,long second)throws SQLException{member(first);member(second);try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES(?,?,?)")){s.setLong(1,first);s.setLong(2,second);s.setObject(3,java.time.OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));s.executeUpdate();ownedBlocks.add(new Block(first,second));}}
    private void revoke(String table,String column){try{execute("REVOKE SELECT("+column+") ON "+table+" FROM pawbridge_f2_reader");}catch(SQLException failure){throw new IllegalStateException(failure);}}
    private void user(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f22-" + id + "@fixture.invalid"); s.setString(3, "F22 synthetic");
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
            throw new IllegalArgumentException("Only owned F22 key cleanup is supported");
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
    private record Block(long first,long second) { }
}
