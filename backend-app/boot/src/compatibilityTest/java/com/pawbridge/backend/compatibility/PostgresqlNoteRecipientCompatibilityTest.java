package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.community.internal.note.application.RecipientRejectedException;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockReader;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F17LegacyRecipientService;
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

/** One UTC, hosted-only reader. The bridge simulates old HTTP failure semantics, not HTTP transport. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlNoteRecipientCompatibilityTest {
    private static final long BASE = 9_971_100L;
    private final Set<Long> ownedUsers = new LinkedHashSet<>(), ownedMembers = new LinkedHashSet<>(), ownedPending = new LinkedHashSet<>();
    private final Set<Block> ownedBlocks = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc;
    private ContactMemberQuery contacts;
    private ReadNoteRecipient candidate;
    private F17LegacyRecipientService original;

    @BeforeAll void openOnlyTheOwnedFixtureAndOneReaderConnection() throws Exception {
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        var config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f17-contact-reader");
        reader = new HikariDataSource(config); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        contacts = new JdbcContactMemberQuery(jdbc, tx);
        var blocks = new JdbcNoteBlockReader(jdbc, tx);
        ContactMemberQuery boundedContacts = id -> { returned(); try { return contacts.get(id); } finally { returned(); } };
        NoteBlockReader boundedBlocks = (first, second) -> { returned(); try { return blocks.blocked(first, second); } finally { returned(); } };
        candidate = new ReadNoteRecipient(boundedContacts, boundedBlocks);
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
        original = new F17LegacyRecipientService(new PrivateNoteRepository(jdbc), client);
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

    @ParameterizedTest @ValueSource(strings = {"normal", "missing", "pending"})
    void givenNormalMissingOrPendingContact__whenReadingTheProjectionAndRecipient__thenActualOriginalAndCandidateAgree(String kind) throws Exception {
        user(BASE + 1, "F17caller" + BASE); long target = BASE + 2;
        if (!kind.equals("missing")) user(target, " F17 raw " + target + " ");
        if (kind.equals("pending")) pending(target);
        String before = snapshot();
        var expected = new com.pawbridge.userservice.contact.ContactMemberQuery(jdbc).get(target);
        assertThat(contacts.get(target)).isEqualTo(new ContactMember(expected.userId(), expected.nickname(), expected.active(), expected.deletionPending()));
        assertThat(expected.userId()).isEqualTo(target);
        assertThat(expected.nickname()).isEqualTo(kind.equals("normal") ? " F17 raw " + target + " " : "탈퇴한 회원");
        if (kind.equals("normal")) same(BASE + 1, target);
        else denied(BASE + 1, target, 403, RecipientRejectedException.Reason.INACTIVE);
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void givenEitherDirectionOfBlocking__whenResolvingRecipient__thenTheOriginalAndCandidateBothReject(boolean reverse) throws Exception {
        pair(); block(reverse ? BASE + 2 : BASE + 1, reverse ? BASE + 1 : BASE + 2);
        String before = snapshot(); denied(BASE + 1, BASE + 2, 403, RecipientRejectedException.Reason.BLOCKED);
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @Test void givenSelfRecipientAndUnavailableBlockSelect__whenResolving__thenSelfRejectionPrecedesTheBlockQuery() throws Exception {
        pair(); String before = snapshot();
        execute("REVOKE SELECT(member_id,blocked_id) ON pawbridge_community.private_note_blocks FROM pawbridge_f2_reader");
        try { denied(BASE + 1, BASE + 1, 403, RecipientRejectedException.Reason.BLOCKED); returned(); }
        finally { execute("GRANT SELECT(member_id,blocked_id) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader"); }
        same(BASE + 1, BASE + 2); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void givenANonpositiveRecipientAfterAnActiveCallerAndFalseBlock__whenResolving__thenLateLoginRejectionMatchesOriginal(long target) throws Exception {
        pair(); String before = snapshot(); denied(BASE + 1, target, 401, RecipientRejectedException.Reason.LOGIN);
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(strings = {"nickname", "pending"})
    void givenRequiredUserSelectRevoked__whenResolvingThenRestoring__thenMemberUnavailableDoesNotBecomeBlockStorageFailure(String column) throws Exception {
        pair(); String before = snapshot();
        String target = column.equals("nickname") ? "nickname) ON pawbridge_user.users" : "user_id) ON pawbridge_user.contact_deletions";
        execute("REVOKE SELECT(" + target + " FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> original.recipient(BASE + 1, BASE + 2)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
            assertThatThrownBy(() -> candidate.read(BASE + 1, BASE + 2)).isInstanceOf(ContactMemberUnavailableException.class);
            assertThat(snapshot()).isEqualTo(before); returned();
        } finally { execute("GRANT SELECT(" + target + " TO pawbridge_f2_reader"); }
        same(BASE + 1, BASE + 2); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"member_id", "blocked_id"})
    void givenRequiredBlockSelectRevokedAndBadTarget__whenResolvingThenRestoring__thenBlockFailurePrecedesLateTargetValidation(String column) throws Exception {
        pair(); String before = snapshot();
        execute("REVOKE SELECT(" + column + ") ON pawbridge_community.private_note_blocks FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> original.recipient(BASE + 1, 0)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> candidate.read(BASE + 1, 0)).isInstanceOf(DataAccessException.class);
            assertThat(snapshot()).isEqualTo(before); returned();
        } finally { execute("GRANT SELECT(" + column + ") ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader"); }
        same(BASE + 1, BASE + 2); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"pawbridge_user.contact_deletions.started_at", "pawbridge_community.private_note_members.member_id", "pawbridge_community.private_note_blocks.created_at"})
    void givenTheMinimalReader__whenSelectingAnUnusedNewColumn__thenPermissionIsDeniedAndTheRecipientStillWorks(String path) throws Exception {
        pair(); String before = snapshot(); int split = path.lastIndexOf('.');
        try (Connection c = reader.getConnection()) {
            assertThatThrownBy(() -> { try (var s = c.createStatement()) {
                s.executeQuery("SELECT " + path.substring(split + 1) + " FROM " + path.substring(0, split) + " WHERE false");
            } }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        same(BASE + 1, BASE + 2); assertThat(snapshot()).isEqualTo(before); returned();
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
        for (var e : Map.of("F17_CALLER", 9_971_001L, "F17_OTHER", 9_971_002L).entrySet()) {
            String token = issuer.createAccessToken(tokenUser(e.getValue()));
            assertThat(authenticator.authenticate("Bearer " + token)).isEqualTo(e.getValue()); tokens.put(e.getKey(), token);
        }
        tokens.put("expired", new JwtProvider(secret, -60_000, 600_000).createAccessToken(tokenUser(9_971_001L)));
        assertThat(tokens.keySet()).containsAll(before).contains("F17_CALLER", "F17_OTHER");
        Files.writeString(file, mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        returned();
    }

    private User tokenUser(long id) { return User.builder().userId(id).email("f17-token@fixture.invalid").name("F17 synthetic")
            .nickname("F17token").provider("GOOGLE").role(Role.ROLE_USER).build(); }
    private void same(long caller, long target) {
        var expected = original.recipient(caller, target); returned();
        assertThat(candidate.read(caller, target)).isEqualTo(new ContactMember(expected.userId(), expected.nickname(), expected.active(), expected.deletionPending())); returned();
    }
    private void denied(long caller, long target, int status, RecipientRejectedException.Reason reason) {
        assertThatThrownBy(() -> original.recipient(caller, target)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status)); returned();
        assertThatThrownBy(() -> candidate.read(caller, target)).isInstanceOfSatisfying(RecipientRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason)); returned();
    }
    private void pair() throws SQLException { user(BASE + 1, "F17caller" + BASE); user(BASE + 2, "F17target" + BASE); }
    private void user(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f17-" + id + "@fixture.invalid"); s.setString(3, "F17 synthetic");
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
            throw new IllegalArgumentException("Only owned F17 key cleanup is supported");
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
