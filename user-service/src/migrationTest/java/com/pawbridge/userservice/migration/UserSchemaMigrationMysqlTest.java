package com.pawbridge.userservice.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.FlywayException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mysql")
class UserSchemaMigrationMysqlTest {
    private UserSchemaMigration.Settings settings;

    @BeforeEach
    void prepare_disposable_database() throws Exception {
        String port = System.getenv("USER_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Set USER_MIGRATION_TEST_PORT for the disposable container");
        }
        String url = "jdbc:mysql://127.0.0.1:" + port + "/pawbridge_user";
        var env = UserSchemaMigrationTest.environment(url);
        env.put("USER_MIGRATION_USERNAME", "root");
        env.put("USER_MIGRATION_PASSWORD", "local_flyway_test_only");
        env.put("USER_MIGRATION_CONFIRM_TARGET", url);
        settings = UserSchemaMigration.Settings.from(env);
        try (var connection = connection(); var statement = connection.createStatement()) {
            // Never clean a DB without the dedicated test container marker.
            try (var marker = statement.executeQuery("SELECT marker FROM flyway_test_guard.user_guard")) {
                if (!marker.next() || !"user-flyway-disposable".equals(marker.getString(1))) {
                    throw new IllegalStateException("Missing disposable database guard");
                }
            }
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
            statement.execute("DROP TABLE IF EXISTS migration_probe");
            // Fixed reverse dependency order within this guarded service schema.
            for (String table : List.of("refresh_tokens", "processed_events", "outbox_events", "favorites", "users")) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    @Test
    void location_without_sql_cannot_report_migration_success() {
        assertThatThrownBy(() -> UserSchemaMigration.execute("migrate", settings,
                "classpath:com/pawbridge/userservice/migration"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No reviewed migrations are packaged");
    }

    @Test
    void existing_schema_is_not_silently_baselined() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_probe (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO migration_probe VALUES (73)");
        }
        assertThatThrownBy(() -> UserSchemaMigration.execute("migrate", settings, "classpath:db/migration"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id FROM migration_probe")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(73);
        }
    }

    @Test
    void initial_schema_matches_all_entities() throws Exception {
        UserSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES "
                     + "WHERE TABLE_SCHEMA = 'pawbridge_user'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(6); // Service tables plus Flyway history.
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", settings.url())
                .applySetting("hibernate.connection.username", settings.username())
                .applySetting("hibernate.connection.password", settings.password())
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        try {
            var metadata = new MetadataSources(registry);
            for (Class<?> entity : List.of(
                    com.pawbridge.userservice.entity.Favorite.class,
                    com.pawbridge.userservice.entity.OutboxEvent.class,
                    com.pawbridge.userservice.entity.ProcessedEvent.class,
                    com.pawbridge.userservice.entity.RefreshToken.class,
                    com.pawbridge.userservice.entity.User.class)) {
                metadata.addAnnotatedClass(entity);
            }
            try (var factory = metadata.buildMetadata().buildSessionFactory()) {
                assertThat(factory.isOpen()).isTrue();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @Tag("rehearsal")
    void explicitly_baselined_existing_schema_preserves_rows() throws Exception {
        UserSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
            statement.execute("INSERT INTO processed_events (event_id, event_type, processed_at) VALUES ('existing-event', 'test', NOW(6))");
        }
        // This explicit baseline runs only on the guarded disposable database.
        UserSchemaMigration.configured(settings, "classpath:db/migration").baseline();
        UserSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        UserSchemaMigration.execute("validate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM processed_events WHERE event_id = 'existing-event'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(settings.url(), settings.username(), settings.password());
    }
}
