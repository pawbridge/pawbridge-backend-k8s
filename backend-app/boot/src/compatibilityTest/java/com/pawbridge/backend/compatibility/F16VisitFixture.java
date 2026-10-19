package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits;
import com.pawbridge.backend.community.internal.visit.application.TodayVisits;
import com.pawbridge.backend.community.internal.visit.infrastructure.JdbcVisitCountReader;
import com.pawbridge.communityservice.visits.VisitCookie;
import com.pawbridge.communityservice.visits.VisitRepository;
import com.pawbridge.communityservice.visits.VisitService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared setup owns only this pool and successfully inserted synthetic dates. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class F16VisitFixture {
    protected F3PostgresqlFixture owner;
    protected HikariDataSource reader;
    protected JdbcTemplate jdbc;
    protected TransactionTemplate transaction;
    protected PlatformTransactionManager originalManager;
    protected ObjectMapper mapper;
    private final Set<LocalDate> owned = new LinkedHashSet<>();

    @BeforeAll final void openOnlyOwnedVisitFixture() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(owner.reads().jdbcUrl()); c.setUsername(owner.reads().applicationUser()); c.setPassword(owner.reads().applicationPassword());
        c.setReadOnly(true); c.setMaximumPoolSize(1); c.setMinimumIdle(0);
        c.setConnectionTimeout(2_000); c.setValidationTimeout(1_000); c.setPoolName("f16-visit-reader");
        reader = new HikariDataSource(c); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        transaction = new TransactionTemplate(manager); transaction.setReadOnly(true); transaction.setTimeout(3);
        originalManager = mock(PlatformTransactionManager.class);
        try (var context = new AnnotationConfigApplicationContext(BootJackson.class)) { mapper = context.getBean(ObjectMapper.class); }
        returned();
    }

    @AfterEach final void removeOnlyOwnDates() throws Exception {
        if (owner != null) {
            try (Connection c = admin(); var statement = c.prepareStatement("DELETE FROM pawbridge_community.daily_visit_count WHERE visit_date=?")) {
                for (LocalDate day : owned) { statement.setDate(1, Date.valueOf(day)); statement.executeUpdate(); }
            }
        }
        owned.clear();
        if (originalManager != null) verifyNoInteractions(originalManager);
        if (reader != null) returned();
    }
    @AfterAll final void closeOnlyOwnPool() { if (reader != null) reader.close(); }

    protected final ReadTodayVisits candidate(Instant instant) {
        return new ReadTodayVisits(new JdbcVisitCountReader(jdbc, transaction), Clock.fixed(instant, ZoneOffset.UTC));
    }
    protected final VisitService original(Instant instant) {
        return new VisitService(new VisitRepository(jdbc), new VisitCookie(Base64.getEncoder().encodeToString(new byte[32])),
                Clock.fixed(instant, ZoneOffset.UTC), originalManager);
    }
    protected final JsonNode same(Instant instant, LocalDate day, long count) throws Exception {
        String before = snapshot();
        TodayVisits actual = candidate(instant).read();
        JsonNode json = mapper.valueToTree(actual);
        assertThat(json).isEqualTo(mapper.valueToTree(original(instant).today()));
        assertThat(actual.date()).isEqualTo(day); assertThat(actual.count()).isEqualTo(count);
        assertThat(json.get("date").asText()).isEqualTo(day.toString());
        assertThat(json.get("count").isIntegralNumber()).isTrue(); assertThat(json.get("count").longValue()).isEqualTo(count);
        Set<String> fields = new LinkedHashSet<>(); json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(Set.of("date", "count"));
        assertThat(snapshot()).isEqualTo(before); returned(); return json;
    }
    protected final void seed(LocalDate day, long count) throws SQLException {
        assertThat(owned).doesNotContain(day);
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_community.daily_visit_count(visit_date,unique_visitors) VALUES(?,?)")) {
            s.setDate(1, Date.valueOf(day)); s.setLong(2, count); s.executeUpdate(); owned.add(day);
        }
    }
    protected final Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT 15,value,owner FROM pawbridge_f15_fixture.marker UNION ALL SELECT 16,value,owner FROM pawbridge_f16_fixture.marker ORDER BY 1")) {
            String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{15, 16}) {
                assertThat(F3PostgresqlFixture.required("F" + stage + "_CI_OWNER")).isEqualTo(run);
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(stage);
                assertThat(rows.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1"); assertThat(rows.getString(3)).isEqualTo(run);
            }
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { c.close(); throw failure; }
        return c;
    }
    protected final void verifyOwnership() { try (Connection ignored = admin()) { } catch (SQLException e) { throw new IllegalStateException(e); } }
    protected final void execute(String sql) throws SQLException { try (Connection c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    protected final String snapshot() throws SQLException {
        String sql = "SELECT md5(json_build_object("
                + "'visits',(SELECT COALESCE(json_agg(v ORDER BY visit_date),'[]') FROM pawbridge_community.daily_visit_count v),"
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
    protected final void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne(); assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
    @Configuration(proxyBeanMethods = false) @ImportAutoConfiguration(JacksonAutoConfiguration.class)
    static class BootJackson { }
}
