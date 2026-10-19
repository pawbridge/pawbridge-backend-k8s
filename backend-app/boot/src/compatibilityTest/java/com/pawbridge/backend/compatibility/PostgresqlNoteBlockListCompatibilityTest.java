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
import com.pawbridge.backend.community.internal.note.application.BlockPage;
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockListReader;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F20LegacyBlockListService;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** One owned reader; original transport is a test bridge. Seoul selects only the thin TSTZ method. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteBlockListCompatibilityTest {
    private static final long BASE = 9_974_100L;
    private final Set<Long> ownedUsers = new LinkedHashSet<>(), ownedMembers = new LinkedHashSet<>(), ownedPending = new LinkedHashSet<>();
    private final Set<Block> ownedBlocks = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc;
    private ContactMemberQuery contacts;
    private ContactMembersQuery batch;
    private NoteBlockListReader blocks;
    private ListNoteBlocks candidate;
    private F20LegacyBlockListService original;
    private UserServiceClient legacyClient;

    @BeforeAll void openOnlyOwnedFixtureAndOneReaderConnection() throws Exception {
        assertThat(System.getProperty("user.timezone")).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        var config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f20-block-list-reader");
        reader = new HikariDataSource(config); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        var single = new JdbcContactMemberQuery(jdbc, tx);
        var multiple = new JdbcContactMembersQuery(jdbc, tx);
        var pages = new JdbcNoteBlockListReader(jdbc, tx);
        contacts = id -> { returned(); try { return single.get(id); } finally { returned(); } };
        batch = ids -> { returned(); try { return multiple.getAll(ids); } finally { returned(); } };
        blocks = new NoteBlockListReader() {
            public long count(long id) { returned(); try { return pages.count(id); } finally { returned(); } }
            public List<NoteBlockListReader.Block> read(long id, int page) { returned(); try { return pages.read(id,page); } finally { returned(); } }
        };
        candidate = new ListNoteBlocks(contacts, blocks, batch);
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
        original = new F20LegacyBlockListService(new PrivateNoteRepository(jdbc), legacyClient);
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
            try (Connection c=admin(); var s=c.prepareStatement("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id=? AND blocked_id=?")) {
                for (Block b:ownedBlocks) { s.setLong(1,b.first()); s.setLong(2,b.second()); s.executeUpdate(); }
            }
            deleteIds("pawbridge_user.contact_deletions","user_id",ownedPending);
            deleteIds("pawbridge_community.private_note_members","member_id",ownedMembers);
            deleteIds("pawbridge_user.users","user_id",ownedUsers);
        }
        ownedBlocks.clear(); ownedPending.clear(); ownedMembers.clear(); ownedUsers.clear();
        if (reader!=null) returned();
    }
    @AfterAll void closeOnlyOwnPool() { if(reader!=null) reader.close(); }

    @Test void givenTwelveOutgoingBlocksReverseOnlyAndDifferentMemberStates__whenPaging__thenActualSourceAndCandidateKeepEveryIdCountOrderAndRawDisplay() throws Exception {
        user(BASE+1,"F20caller"+BASE); user(BASE+2,"  F20raw"+BASE+"  "); user(BASE+3,"F20normal"+BASE); user(BASE+4,"F20pending"+BASE); pending(BASE+4);
        for(long id=BASE+2;id<=BASE+13;id++) block(BASE+1,id,Instant.parse(id==BASE+2 ? "2031-01-02T00:00:00.123456Z" : "2031-01-01T00:00:00.123456Z"));
        block(BASE+14,BASE+1); String before=snapshot();
        var first=same(BASE+1,0); var second=same(BASE+1,1); var empty=same(BASE+1,2);
        assertThat(first.totalElements()).isEqualTo(12); assertThat(first.totalPages()).isEqualTo(2); assertThat(first.number()).isZero();
        assertThat(first.content()).extracting(BlockPage.BlockView::memberId).containsExactlyElementsOf(java.util.stream.Stream.concat(java.util.stream.Stream.of(BASE+2),java.util.stream.LongStream.rangeClosed(BASE+5,BASE+13).boxed().sorted(java.util.Comparator.reverseOrder())).toList());
        assertThat(second.content()).extracting(BlockPage.BlockView::memberId).containsExactly(BASE+4,BASE+3);
        assertThat(first.content().getFirst().nickname()).isEqualTo("  F20raw"+BASE+"  ");
        assertThat(second.content().getFirst().nickname()).isEqualTo("탈퇴한 회원");
        assertThat(first.content().get(1).nickname()).isEqualTo("탈퇴한 회원");
        assertThat(second.content().getLast().nickname()).isEqualTo("F20normal"+BASE);
        assertThat(empty.content()).isEmpty(); assertThat(empty.totalElements()).isEqualTo(12); assertThat(empty.number()).isEqualTo(2);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"missing","pending"})
    void givenInactiveCallerAndRevokedBlockOwnerColumn__whenReading__thenCaller403PrecedesAnyBlockSelect(String kind) throws Exception {
        if(kind.equals("pending")){user(BASE+1,"F20caller"+BASE);pending(BASE+1);} String before=snapshot();
        execute("REVOKE SELECT(member_id) ON pawbridge_community.private_note_blocks FROM pawbridge_f2_reader");
        try { denied(BASE+1,0,403,NoteBlockListRejectedException.Reason.INACTIVE); assertThat(snapshot()).isEqualTo(before); }
        finally { execute("GRANT SELECT(member_id) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader"); }
    }

    @Test void givenRevokedPendingSelect__whenPageIsInvalidOrValid__thenValidation400PrecedesMember503AndRestorationRecovers() throws Exception {
        user(BASE+1,"F20caller"+BASE); block(BASE+1,BASE+2); String before=snapshot();
        execute("REVOKE SELECT(user_id) ON pawbridge_user.contact_deletions FROM pawbridge_f2_reader");
        try {
            denied(BASE+1,-1,400,NoteBlockListRejectedException.Reason.INVALID_PAGE);
            assertThatThrownBy(()->original.blocks(BASE+1,0)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503)); returned();
            assertThatThrownBy(()->candidate.read(BASE+1,0)).isInstanceOf(ContactMemberUnavailableException.class); returned();
            assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT(user_id) ON pawbridge_user.contact_deletions TO pawbridge_f2_reader"); }
        same(BASE+1,0); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"member_id","blocked_id","created_at"})
    void givenRequiredBlockColumnRevoked__whenCountingThenListing__thenActualSourceAndCandidateFailAtTheOwnedBoundaryAndRecover(String column) throws Exception {
        user(BASE+1,"F20caller"+BASE); block(BASE+1,BASE+2); String before=snapshot();
        execute("REVOKE SELECT("+column+") ON pawbridge_community.private_note_blocks FROM pawbridge_f2_reader");
        try {
            if(!column.equals("member_id")){assertThat(blocks.count(BASE+1)).isEqualTo(1);returned();assertThat(new PrivateNoteRepository(jdbc).blockCount(BASE+1)).isEqualTo(1);returned();}
            assertThatThrownBy(()->original.blocks(BASE+1,0)).isInstanceOf(DataAccessException.class); returned();
            assertThatThrownBy(()->candidate.read(BASE+1,0)).isInstanceOf(DataAccessException.class); returned(); assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT("+column+") ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader"); }
        same(BASE+1,0); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void givenUserNicknameRevokedOnlyAfterTheListConnectionReturns__whenBatchDisplayRuns__thenBothMemberBoundariesFailAndRecoverWithoutHiddenPerRowFallback() throws Exception {
        user(BASE+1,"F20caller"+BASE); block(BASE+1,BASE+2); String before=snapshot();
        var hit=new AtomicBoolean();
        var hookedOriginal=new F20LegacyBlockListService(new PrivateNoteRepository(jdbc){
            @Override public List<PrivateNoteRepository.Block> blocks(long id,int page){var rows=super.blocks(id,page);returned();revokeNickname();hit.set(true);return rows;}
        },legacyClient);
        try { assertThatThrownBy(()->hookedOriginal.blocks(BASE+1,0)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));returned();assertThat(hit).isTrue(); }
        finally { execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        hit.set(false);
        var hookedReader=new NoteBlockListReader(){
            public long count(long id){return blocks.count(id);}
            public List<NoteBlockListReader.Block> read(long id,int page){var rows=blocks.read(id,page);returned();revokeNickname();hit.set(true);return rows;}
        };
        try { assertThatThrownBy(()->new ListNoteBlocks(contacts,hookedReader,batch).read(BASE+1,0)).isInstanceOf(ContactMemberUnavailableException.class);returned();assertThat(hit).isTrue(); }
        finally { execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        same(BASE+1,0); assertThat(snapshot()).isEqualTo(before);
    }
    private void revokeNickname(){try{execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");}catch(SQLException failure){throw new IllegalStateException(failure);}}

    @Test void givenTstzMicroseconds__whenReadingAndSerializing__thenInstantIsStableWithoutLocalZoneShift() throws Exception {
        user(BASE+1,"F20caller"+BASE); block(BASE+1,BASE+2); String before=snapshot();
        var actual=same(BASE+1,0); var instant=Instant.parse("2031-01-01T00:00:00.123456Z");
        assertThat(actual.content().getFirst().createdAt()).isEqualTo(instant);
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=context.getBean(ObjectMapper.class); assertThat(mapper.valueToTree(actual).path("content").get(0).path("createdAt").asText()).isEqualTo(instant.toString());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test @Order(Integer.MAX_VALUE)
    void givenOriginalIssuerAndOwnedFile__whenPreparingOnlyTheUsedJarPrincipal__thenPreserveAllPreviousKeysAndPermissions() throws Exception {
        verifyOwnership(); String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue(); assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper(); Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(file),new TypeReference<Map<String,String>>(){})); var oldKeys=Set.copyOf(tokens.keySet());
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var user=User.builder().userId(9_974_001L).email("f20-token@fixture.invalid").name("F20 synthetic").nickname("F20token").provider("GOOGLE").role(Role.ROLE_USER).build();
        String token=new JwtProvider(secret,600_000,600_000).createAccessToken(user);
        assertThat(new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).authenticate("Bearer "+token)).isEqualTo(9_974_001L);
        tokens.put("F20_CALLER",token); assertThat(tokens.keySet()).containsAll(oldKeys); Files.writeString(file,mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); returned();
    }
    private BlockPage same(long caller,int page){
        var expected=original.blocks(caller,page);returned();var actual=candidate.read(caller,page);returned();
        assertThat(actual.totalElements()).isEqualTo(expected.totalElements()); assertThat(actual.totalPages()).isEqualTo(expected.totalPages());assertThat(actual.number()).isEqualTo(expected.number());
        assertThat(actual.content()).hasSize(expected.content().size());
        for(int i=0;i<expected.content().size();i++){var a=actual.content().get(i);var e=expected.content().get(i);assertThat(a.memberId()).isEqualTo(e.memberId());assertThat(a.nickname()).isEqualTo(e.nickname());assertThat(a.createdAt()).isEqualTo(e.createdAt());}
        return actual;
    }
    private void denied(long caller,int page,int status,NoteBlockListRejectedException.Reason reason){
        assertThatThrownBy(()->original.blocks(caller,page)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(status));returned();
        assertThatThrownBy(()->candidate.read(caller,page)).isInstanceOfSatisfying(NoteBlockListRejectedException.class,e->assertThat(e.reason()).isEqualTo(reason));returned();
    }
    private void user(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f20-" + id + "@fixture.invalid"); s.setString(3, "F20 synthetic");
            s.setString(4, nickname); s.setString(5, "GOOGLE"); s.setString(6, "ROLE_USER"); s.executeUpdate(); ownedUsers.add(id);
        }
    }
    private void pending(long id) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(?)")) {
            s.setLong(1, id); s.executeUpdate(); ownedPending.add(id);
        }
    }
    private void block(long first, long second) throws SQLException { block(first,second,Instant.parse("2031-01-01T00:00:00.123456Z")); }
    private void block(long first, long second, Instant timestamp) throws SQLException {
        for (long id : new long[]{first, second}) {
            if (!ownedMembers.contains(id)) try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(?)")) {
                s.setLong(1, id); s.executeUpdate(); ownedMembers.add(id);
            }
        }
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES(?,?,?)")) {
            s.setLong(1, first); s.setLong(2, second); s.setObject(3,java.time.OffsetDateTime.ofInstant(timestamp,java.time.ZoneOffset.UTC)); s.executeUpdate(); ownedBlocks.add(new Block(first, second));
        }
    }
    private void deleteIds(String table, String column, Set<Long> ids) throws SQLException {
        if (!Set.of("pawbridge_user.contact_deletions.user_id", "pawbridge_community.private_note_members.member_id", "pawbridge_user.users.user_id").contains(table + "." + column))
            throw new IllegalArgumentException("Only owned F20 key cleanup is supported");
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
    private record Block(long first, long second) { }
}
