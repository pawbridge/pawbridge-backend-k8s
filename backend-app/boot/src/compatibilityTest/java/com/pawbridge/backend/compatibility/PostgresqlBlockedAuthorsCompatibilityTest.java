package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.application.BlockStatusRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockStatusReader;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F18LegacyBlockedAuthorsService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** One owned UTC reader and unchanged F17 grants; the legacy member transport boundary is simulated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlBlockedAuthorsCompatibilityTest {
    private static final long BASE = 9_972_100L;
    private final Set<Long> ownedUsers = new LinkedHashSet<>(), ownedMembers = new LinkedHashSet<>(), ownedPending = new LinkedHashSet<>();
    private final Set<Block> ownedBlocks = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc;
    private ContactMemberQuery contacts;
    private ReadBlockedAuthors candidate;
    private F18LegacyBlockedAuthorsService original;

    @BeforeAll void openOnlyTheOwnedFixtureAndOneReaderConnection() throws Exception {
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        var config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f18-block-status-reader");
        reader = new HikariDataSource(config); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        contacts = new JdbcContactMemberQuery(jdbc, tx);
        var blocks = new JdbcNoteBlockStatusReader(jdbc, tx);
        ContactMemberQuery boundedContacts = id -> { returned(); try { return contacts.get(id); } finally { returned(); } };
        NoteBlockStatusReader boundedBlocks = (first, second) -> { returned(); try { return blocks.blockedAuthors(first, second); } finally { returned(); } };
        candidate = new ReadBlockedAuthors(boundedContacts, boundedBlocks);
        var legacyContacts = new com.pawbridge.userservice.contact.ContactMemberQuery(jdbc);
        var client = mock(UserServiceClient.class);
        when(client.getContactMember(anyLong())).thenAnswer(call -> {
            returned();
            try {
                var value = legacyContacts.get(call.getArgument(0, Long.class));
                return new PrivateNoteModels.ContactMember(value.userId(), value.nickname(), value.active(), value.deletionPending());
            } catch (RuntimeException failure) {
                // The actual old User endpoint turns JDBC failure into HTTP500; Feign then sees non404.
                var request = Request.create(Request.HttpMethod.GET, "https://fixture.invalid/contact", Map.of(),
                        new byte[0], StandardCharsets.UTF_8, null);
                throw FeignException.errorStatus("synthetic", Response.builder().status(500).reason("synthetic")
                        .request(request).headers(Map.of()).build());
            } finally { returned(); }
        });
        original = new F18LegacyBlockedAuthorsService(new PrivateNoteRepository(jdbc), client);
        returned();
    }

    @AfterEach void removeOnlySuccessfulOwnedInsertsInForeignKeyOrder() throws Exception {
        if (owner != null) {
            try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id=? AND blocked_id=?")) {
                for (Block b : ownedBlocks) { s.setLong(1, b.first()); s.setLong(2, b.second()); s.executeUpdate(); }
            }
            deleteIds("pawbridge_user.contact_deletions", "user_id", ownedPending);
            deleteIds("pawbridge_community.private_note_members", "member_id", ownedMembers);
            deleteIds("pawbridge_user.users", "user_id", ownedUsers);
        }
        ownedBlocks.clear(); ownedPending.clear(); ownedMembers.clear(); ownedUsers.clear();
        if (reader != null) returned();
    }
    @AfterAll void closeOnlyOwnPool() { if (reader != null) reader.close(); }

    @Test void givenMoreThanOnePageOfOutgoingBlocksAndReverseOnlyRows__whenReadingUnorderedDuplicateIds__thenActualOriginalAndCandidateReturnOnlySortedOutgoingTargets() throws Exception {
        user(BASE + 1, "F18caller" + BASE);
        var targets = new java.util.ArrayList<Long>();
        for (long offset = 2; offset <= 13; offset++) { block(BASE + 1, BASE + offset); targets.add(BASE + offset); }
        block(BASE + 14, BASE + 1);
        java.util.Collections.reverse(targets);
        var input = new java.util.ArrayList<>(targets); input.add(BASE + 2); input.add(BASE + 14); input.add(BASE + 99); input.add(BASE + 1);
        String before = snapshot();
        var expected = original.blockedAuthors(BASE + 1, input).memberIds(); returned();
        var actual = candidate.read(BASE + 1, input).memberIds(); returned();
        assertThat(actual).containsExactlyElementsOf(expected).hasSize(12).isSorted();
        assertThat(actual).doesNotContain(BASE + 1, BASE + 14, BASE + 99);
        assertThat(candidate.read(BASE + 1, java.util.List.of(BASE + 14, BASE + 99)).memberIds()).isEmpty(); returned();
        assertThat(original.blockedAuthors(BASE + 1, java.util.List.of(BASE + 14, BASE + 99)).memberIds()).isEmpty(); returned();
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "pending"})
    void givenAnUnavailableCurrentMemberAndRevokedBlockSelect__whenReading__thenInactiveRejectionPrecedesAnyBlockQuery(String kind) throws Exception {
        if (kind.equals("pending")) { user(BASE + 1, "F18pending" + BASE); pending(BASE + 1); }
        String before = snapshot();
        execute("REVOKE SELECT(member_id,blocked_id) ON pawbridge_community.private_note_blocks FROM pawbridge_f2_reader");
        try {
            denied(BASE + 1, java.util.List.of(BASE + 2), 403, BlockStatusRejectedException.Reason.INACTIVE);
            assertThat(snapshot()).isEqualTo(before); returned();
        } finally { execute("GRANT SELECT(member_id,blocked_id) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader"); }
    }

    @Test void givenRevokedUserPendingSelect__whenInputIsInvalidOrValid__thenListValidationPrecedesMemberUnavailableAndRestorationRecovers() throws Exception {
        user(BASE + 1, "F18caller" + BASE); block(BASE + 1, BASE + 2); String before = snapshot();
        execute("REVOKE SELECT(user_id) ON pawbridge_user.contact_deletions FROM pawbridge_f2_reader");
        try {
            denied(BASE + 1, java.util.List.of(0L), 400, BlockStatusRejectedException.Reason.INVALID_MEMBERS);
            assertThatThrownBy(() -> original.blockedAuthors(BASE + 1, java.util.List.of(BASE + 2))).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(503)); returned();
            assertThatThrownBy(() -> candidate.read(BASE + 1, java.util.List.of(BASE + 2))).isInstanceOf(ContactMemberUnavailableException.class); returned();
            assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT(user_id) ON pawbridge_user.contact_deletions TO pawbridge_f2_reader"); }
        same(BASE + 1, java.util.List.of(BASE + 2)); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"member_id", "blocked_id"})
    void givenRequiredBlockSelectRevoked__whenReadingThenRestoring__thenBothRepositoriesFailAndRecoverWithoutChangingBusinessRows(String column) throws Exception {
        user(BASE + 1, "F18caller" + BASE); block(BASE + 1, BASE + 2); String before = snapshot();
        execute("REVOKE SELECT(" + column + ") ON pawbridge_community.private_note_blocks FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> original.blockedAuthors(BASE + 1, java.util.List.of(BASE + 2))).isInstanceOf(DataAccessException.class); returned();
            assertThatThrownBy(() -> candidate.read(BASE + 1, java.util.List.of(BASE + 2))).isInstanceOf(DataAccessException.class); returned();
            assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT(" + column + ") ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader"); }
        same(BASE + 1, java.util.List.of(BASE + 2)); assertThat(snapshot()).isEqualTo(before);
    }


    @Test @Order(Integer.MAX_VALUE)
    void givenTheOriginalIssuerAndOwnedFile__whenPreparingOnlyUsedJarPrincipals__thenPreserveAllOldKeysWithoutPrintingTokens() throws Exception {
        verifyOwnership(); String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file = Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-" + run + ".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper = new ObjectMapper();
        Map<String, String> tokens = new LinkedHashMap<>(mapper.readValue(Files.readString(file), new TypeReference<Map<String, String>>() { }));
        Set<String> before = Set.copyOf(tokens.keySet()); String secret = F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var issuer = new JwtProvider(secret, 600_000, 600_000);
        var authenticator = new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
        for (var e : Map.of("F18_CALLER", 9_972_001L).entrySet()) {
            String token = issuer.createAccessToken(tokenUser(e.getValue()));
            assertThat(authenticator.authenticate("Bearer " + token)).isEqualTo(e.getValue()); tokens.put(e.getKey(), token);
        }
        tokens.put("expired", new JwtProvider(secret, -60_000, 600_000).createAccessToken(tokenUser(9_972_001L)));
        assertThat(tokens.keySet()).containsAll(before).contains("F18_CALLER");
        Files.writeString(file, mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        returned();
    }

    private User tokenUser(long id) { return User.builder().userId(id).email("f18-token@fixture.invalid").name("F18 synthetic")
            .nickname("F18token").provider("GOOGLE").role(Role.ROLE_USER).build(); }
    private void same(long caller, java.util.List<Long> ids) {
        var expected = original.blockedAuthors(caller, ids); returned();
        assertThat(candidate.read(caller, ids).memberIds()).containsExactlyElementsOf(expected.memberIds()); returned();
    }
    private void denied(long caller, java.util.List<Long> ids, int status, BlockStatusRejectedException.Reason reason) {
        assertThatThrownBy(() -> original.blockedAuthors(caller, ids)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status)); returned();
        assertThatThrownBy(() -> candidate.read(caller, ids)).isInstanceOfSatisfying(BlockStatusRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason)); returned();
    }
    private void user(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f18-" + id + "@fixture.invalid"); s.setString(3, "F18 synthetic");
            s.setString(4, nickname); s.setString(5, "GOOGLE"); s.setString(6, "ROLE_USER"); s.executeUpdate(); ownedUsers.add(id);
        }
    }
    private void pending(long id) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(?)")) {
            s.setLong(1, id); s.executeUpdate(); ownedPending.add(id);
        }
    }
    private void block(long first, long second) throws SQLException {
        for (long id : new long[]{first, second}) {
            if (!ownedMembers.contains(id)) try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(?)")) {
                s.setLong(1, id); s.executeUpdate(); ownedMembers.add(id);
            }
        }
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES(?,?,TIMESTAMPTZ '2031-01-01T00:00:00Z')")) {
            s.setLong(1, first); s.setLong(2, second); s.executeUpdate(); ownedBlocks.add(new Block(first, second));
        }
    }
    private void deleteIds(String table, String column, Set<Long> ids) throws SQLException {
        if (!Set.of("pawbridge_user.contact_deletions.user_id", "pawbridge_community.private_note_members.member_id", "pawbridge_user.users.user_id").contains(table + "." + column))
            throw new IllegalArgumentException("Only owned F18 key cleanup is supported");
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
