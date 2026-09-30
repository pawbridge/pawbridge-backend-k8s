package com.pawbridge.communityservice.migration;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgresql")
class AnimalReportSearchMigrationTest {
    @Test
    void v4_to_v5_preserves_old_reports_without_inferred_classification() throws Exception {
        String port = System.getenv("COMMUNITY_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Disposable port required");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        try (var connection = DriverManager.getConnection(url, "postgres", "local_pg_test_only");
             var statement = connection.createStatement()) {
            try (var guard = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!guard.next() || !"services-pg-disposable".equals(guard.getString(1)) || guard.next())
                    throw new IllegalStateException("Disposable guard required");
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_community CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_community");
            var settings = CommunityPostgresqlMigration.Settings.from(CommunityPostgresqlMigrationTest.environment(url));
            var configured = CommunityPostgresqlMigration.configured(settings);
            Flyway.configure().configuration(configured.getConfiguration()).target("4").load().migrate();
            statement.execute("""
                    INSERT INTO pawbridge_community.animal_reports
                    (author_id, report_kind, description, occurred_on, region, species, created_at, updated_at)
                    VALUES (101, 'MISSING', '기존 기록', '2026-09-01', '서울 마포구 상암동', '믹스견', now(), now())
                    """);
            assertThat(configured.migrate().migrationsExecuted).isEqualTo(1);
            configured.validate();
            assertThat(configured.migrate().migrationsExecuted).isZero();
            try (var rows = statement.executeQuery("SELECT region,species,province,district,animal_type FROM pawbridge_community.animal_reports")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("region")).isEqualTo("서울 마포구 상암동");
                assertThat(rows.getString("species")).isEqualTo("믹스견");
                assertThat(rows.getString("province")).isNull();
                assertThat(rows.getString("district")).isNull();
                assertThat(rows.getString("animal_type")).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
