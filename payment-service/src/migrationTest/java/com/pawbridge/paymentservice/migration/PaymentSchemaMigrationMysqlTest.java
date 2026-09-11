package com.pawbridge.paymentservice.migration;

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
class PaymentSchemaMigrationMysqlTest {
    private PaymentSchemaMigration.Settings settings;

    @BeforeEach
    void prepare_disposable_database() throws Exception {
        String port = System.getenv("PAYMENT_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Set PAYMENT_MIGRATION_TEST_PORT for the disposable container");
        }
        String url = "jdbc:mysql://127.0.0.1:" + port + "/pawbridge_payment";
        var env = PaymentSchemaMigrationTest.environment(url);
        env.put("PAYMENT_MIGRATION_USERNAME", "root");
        env.put("PAYMENT_MIGRATION_PASSWORD", "local_flyway_test_only");
        env.put("PAYMENT_MIGRATION_CONFIRM_TARGET", url);
        settings = PaymentSchemaMigration.Settings.from(env);
        try (var connection = connection(); var statement = connection.createStatement()) {
            // Never clean a DB without the dedicated test container marker.
            try (var marker = statement.executeQuery("SELECT marker FROM flyway_test_guard.payment_guard")) {
                if (!marker.next() || !"payment-flyway-disposable".equals(marker.getString(1))) {
                    throw new IllegalStateException("Missing disposable database guard");
                }
            }
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
            statement.execute("DROP TABLE IF EXISTS migration_probe");
            // Fixed reverse dependency order within this guarded service schema.
            for (String table : List.of("payments", "outbox")) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    @Test
    void location_without_sql_cannot_report_migration_success() {
        assertThatThrownBy(() -> PaymentSchemaMigration.execute("migrate", settings,
                "classpath:com/pawbridge/paymentservice/migration"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No reviewed migrations are packaged");
    }

    @Test
    void existing_schema_is_not_silently_baselined() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_probe (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO migration_probe VALUES (73)");
        }
        assertThatThrownBy(() -> PaymentSchemaMigration.execute("migrate", settings, "classpath:db/migration"))
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
        PaymentSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES "
                     + "WHERE TABLE_SCHEMA = 'pawbridge_payment'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(3); // Service tables plus Flyway history.
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
                    com.pawbridge.paymentservice.common.entity.Outbox.class,
                    com.pawbridge.paymentservice.domain.payment.entity.Payment.class)) {
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
        PaymentSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
            statement.execute("INSERT INTO outbox (id, aggregate_id, aggregate_type, created_at, event_type, payload) VALUES (73, 'existing-aggregate', 'test', NOW(6), 'test', '{}')");
        }
        // This explicit baseline runs only on the guarded disposable database.
        PaymentSchemaMigration.configured(settings, "classpath:db/migration").baseline();
        PaymentSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        PaymentSchemaMigration.execute("validate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM outbox WHERE id = 73 AND aggregate_id = 'existing-aggregate'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(settings.url(), settings.username(), settings.password());
    }
}
