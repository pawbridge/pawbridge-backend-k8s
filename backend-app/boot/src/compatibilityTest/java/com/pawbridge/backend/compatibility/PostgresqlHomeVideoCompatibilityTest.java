package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos;
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoReader;
import com.pawbridge.communityservice.curation.HomeVideoRepository;
import com.pawbridge.communityservice.curation.HomeVideoService;
import com.pawbridge.communityservice.curation.YouTubeVideoClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real original home() versus the candidate, on only the hosted run-owned fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresqlHomeVideoCompatibilityTest {
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00.123456Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(29));
    private static final Instant CREATED = Instant.parse("2025-01-01T18:04:05.123456Z");
    private final Set<UUID> owned = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private ReadHomeVideos candidate;
    private HomeVideoService original;
    private PlatformTransactionManager originalManager;
    private YouTubeVideoClient youtube;
    private ObjectMapper mapper;
    private int videoNumber;

    @BeforeAll void openOnlyOwnedHomeFixtureAndActualBootMapper() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(owner.reads().jdbcUrl()); c.setUsername(owner.reads().applicationUser()); c.setPassword(owner.reads().applicationPassword());
        c.setReadOnly(true); c.setMaximumPoolSize(1); c.setMinimumIdle(0);
        c.setConnectionTimeout(2_000); c.setValidationTimeout(1_000); c.setPoolName("f15-home-reader");
        reader = new HikariDataSource(c);
        var jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        candidate = new ReadHomeVideos(new JdbcHomeVideoReader(jdbc, tx), clock);
        youtube = mock(YouTubeVideoClient.class); originalManager = mock(PlatformTransactionManager.class);
        original = new HomeVideoService(new HomeVideoRepository(jdbc), youtube, clock, originalManager);
        try (var mapping = new AnnotationConfigApplicationContext(BootJackson.class)) { mapper = mapping.getBean(ObjectMapper.class); }
        returned();
    }

    @AfterEach void removeOnlySuccessfullyInsertedVideoRows() throws Exception {
        if (owner != null) {
            try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM pawbridge_community.home_videos WHERE id=?")) {
                for (UUID id : owned) { s.setObject(1, id); s.executeUpdate(); }
            }
        }
        owned.clear();
        if (youtube != null) verifyNoInteractions(youtube, originalManager);
        if (reader != null) returned();
    }
    @AfterAll void closeOnlyOwnPool() { if (reader != null) reader.close(); }

    @Test void givenExactMicrosecondCutoffAndVisibilityFlags__whenReading__thenOnlyStrictlyFreshPublishedAvailableRowsMatch() throws Exception {
        seed(id(1), true, true, 0, CREATED, CUTOFF.minusNanos(1_000), null, null, null, null);
        seed(id(2), true, true, 0, CREATED, CUTOFF, null, null, null, null);
        seed(id(3), true, true, 0, CREATED, CUTOFF.plusNanos(1_000), null, null, null, null);
        seed(id(4), false, true, 0, CREATED, NOW, null, null, null, null);
        seed(id(5), true, false, 0, CREATED, NOW, null, null, null, null);
        seed(id(6), true, true, 0, CREATED, null, null, null, null, null);
        seed(id(7), true, true, 1, CREATED, NOW.plusSeconds(60), null, null, null, null);
        String before = snapshot();
        assertThat(candidate.read()).extracting(v -> v.id()).containsExactly(id(3), id(7));
        sameJson(); assertThat(snapshot()).isEqualTo(before); returned();
    }

    @Test void givenMoreThanThreeRowsWithUuidTies__whenReading__thenPostgresqlUnsignedUuidOrderAndLimitArePreserved() throws Exception {
        List<UUID> ids = List.of(UUID.fromString("00000000-0000-0000-0000-000000000015"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-fffffffffff5"),
                UUID.fromString("80000000-0000-0000-0000-000000000015"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff5"));
        for (UUID id : List.of(ids.get(3), ids.get(1), ids.get(2), ids.get(0))) {
            seed(id, true, true, 0, CREATED, NOW, "raw", "", "", null);
        }
        String before = snapshot();
        assertThat(candidate.read()).extracting(v -> v.id()).containsExactlyElementsOf(ids.subList(0, 3));
        sameJson(); assertThat(snapshot()).isEqualTo(before); returned();
    }

    @Test void givenDifferentPositionsAndCreationInstants__whenReading__thenPositionPrecedesCreationTimeAndNoPositivePositionPolicyIsAdded() throws Exception {
        seed(id(1), true, true, 1, CREATED.minusSeconds(100), NOW, null, null, null, null);
        seed(id(2), true, true, -1, CREATED.plusSeconds(100), NOW, null, null, null, null);
        seed(id(3), true, true, 0, CREATED, NOW, null, null, null, null);
        seed(id(4), true, true, 0, CREATED.minusNanos(1_000), NOW, null, null, null, null);
        String before = snapshot();
        assertThat(candidate.read()).extracting(v -> v.id()).containsExactly(id(2), id(4), id(3));
        sameJson(); assertThat(snapshot()).isEqualTo(before); returned();
    }

    @Test void givenNullableAndRawMetadata__whenSerializingThroughActualBootMapper__thenElevenFieldsAndInstantMicrosecondsMatchOriginal() throws Exception {
        seed(id(1), true, true, 0, CREATED, NOW, null, null, null, null);
        seed(id(2), true, true, 1, CREATED, NOW, "", "  ", "https://fixture.invalid/raw?x=%ED%95%9C", Long.MAX_VALUE);
        seed(id(3), true, true, 2, CREATED, NOW, "raw title", "raw channel", "", -1L);
        String before = snapshot(); JsonNode json = sameJson();
        Set<String> fields = new LinkedHashSet<>(); json.get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(Set.of("id", "videoId", "published", "position", "createdAt", "title", "channelTitle", "thumbnailUrl", "durationSeconds", "available", "checkedAt"));
        for (String field : List.of("title", "channelTitle", "thumbnailUrl", "durationSeconds")) {
            assertThat(json.get(0).has(field)).isTrue(); assertThat(json.get(0).get(field).isNull()).isTrue();
        }
        assertThat(json.get(0).get("createdAt").asText()).isEqualTo(CREATED.toString());
        assertThat(json.get(0).get("checkedAt").asText()).isEqualTo(NOW.toString());
        assertThat(json.get(1).get("title").asText()).isEmpty(); assertThat(json.get(1).get("channelTitle").asText()).isEqualTo("  ");
        assertThat(json.get(1).get("durationSeconds").longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(json.get(2).get("durationSeconds").longValue()).isEqualTo(-1);
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @Test void givenNoHomeRows__whenReading__thenBothReturnAnEmptyArrayWithoutBoardWritesOrYoutubeCalls() throws Exception {
        String before = snapshot(); assertThat(sameJson()).isEmpty(); assertThat(candidate.read()).isEmpty();
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(strings = {"title", "checked_at"})
    void givenRequiredProjectionOrPredicateSelectRevoked__whenReadingThenRestoring__thenFailureDoesNotBecomeEmptyAndPoolRecovers(String column) throws Exception {
        seed(id(1), true, true, 0, CREATED, NOW, null, null, null, null); String before = snapshot();
        execute("REVOKE SELECT(" + column + ") ON pawbridge_community.home_videos FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> candidate.read()).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> original.home()).isInstanceOf(DataAccessException.class);
            assertThat(snapshot()).isEqualTo(before); returned();
        } finally { execute("GRANT SELECT(" + column + ") ON pawbridge_community.home_videos TO pawbridge_f2_reader"); }
        assertThat(sameJson()).hasSize(1); assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(strings = {"pawbridge_f2_reader", "pawbridge_f3_writer"})
    void givenNoBoardAccess__whenSelectingEvenNoRows__thenPrivilegeDeniedAndThePublicProjectionRemainsUsable(String role) throws Exception {
        String before = snapshot();
        String password = role.equals(F3PostgresqlFixture.WRITER) ? owner.writerPassword() : owner.reads().applicationPassword();
        verifyOwnership();
        try (Connection c = DriverManager.getConnection(owner.reads().jdbcUrl(), role, password)) {
            assertThatThrownBy(() -> {
                try (var s = c.createStatement()) { s.executeQuery("SELECT revision FROM pawbridge_community.home_video_board WHERE false"); }
            }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        assertThat(sameJson()).isEmpty(); assertThat(snapshot()).isEqualTo(before); returned();
    }

    private JsonNode sameJson() {
        JsonNode expected = mapper.valueToTree(original.home()); JsonNode actual = mapper.valueToTree(candidate.read());
        assertThat(actual).isEqualTo(expected); verifyNoInteractions(youtube, originalManager); return actual;
    }
    private UUID id(int number) { return UUID.fromString("f1500000-0000-4000-8000-" + String.format("%012d", number)); }
    private void seed(UUID id, boolean published, boolean available, int position, Instant created, Instant checked,
                      String title, String channel, String thumbnail, Long duration) throws SQLException {
        assertThat(owned).doesNotContain(id);
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_community.home_videos(id,video_id,published,position,created_at,title,channel_title,thumbnail_url,duration_seconds,available,checked_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setObject(1, id); s.setString(2, "F15" + String.format("%08d", ++videoNumber)); s.setBoolean(3, published); s.setInt(4, position);
            s.setTimestamp(5, Timestamp.from(created)); s.setString(6, title); s.setString(7, channel); s.setString(8, thumbnail);
            s.setObject(9, duration); s.setBoolean(10, available); s.setTimestamp(11, checked == null ? null : Timestamp.from(checked));
            s.executeUpdate(); owned.add(id);
        }
    }
    private Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT 14,value,owner FROM pawbridge_f14_fixture.marker UNION ALL SELECT 15,value,owner FROM pawbridge_f15_fixture.marker ORDER BY 1")) {
            String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{14, 15}) {
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
                + "'videos',(SELECT COALESCE(json_agg(v ORDER BY id),'[]') FROM pawbridge_community.home_videos v),"
                + "'board',(SELECT COALESCE(json_agg(b ORDER BY id),'[]') FROM pawbridge_community.home_video_board b),"
                + "'favorites',(SELECT COALESCE(json_agg(f ORDER BY favorite_id),'[]') FROM pawbridge_user.favorites f),"
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
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
    @Configuration(proxyBeanMethods = false) @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    static class BootJackson { }
}
