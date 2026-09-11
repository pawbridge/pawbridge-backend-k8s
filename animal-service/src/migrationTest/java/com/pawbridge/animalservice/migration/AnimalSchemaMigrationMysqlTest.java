package com.pawbridge.animalservice.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.entity.SyncHistory;
import com.pawbridge.animalservice.entity.ProcessedEvent;
import com.pawbridge.animalservice.entity.OutboxEvent;
import com.pawbridge.animalservice.chatbot.entity.ChatbotSession;
import com.pawbridge.animalservice.chatbot.entity.ChatbotMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotBlockLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.FlywayException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mysql")
class AnimalSchemaMigrationMysqlTest {
    private AnimalSchemaMigration.Settings settings;

    @BeforeEach
    void prepare_disposable_database() throws Exception {
        String port = System.getenv("ANIMAL_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Set ANIMAL_MIGRATION_TEST_PORT for the disposable container");
        }
        String url = "jdbc:mysql://127.0.0.1:" + port + "/pawbridge_animal";
        var env = AnimalSchemaMigrationTest.environment(url);
        env.put("ANIMAL_MIGRATION_USERNAME", "root");
        env.put("ANIMAL_MIGRATION_PASSWORD", "local_flyway_test_only");
        env.put("ANIMAL_MIGRATION_CONFIRM_TARGET", url);
        settings = AnimalSchemaMigration.Settings.from(env);
        try (var connection = connection(); var statement = connection.createStatement()) {
            // Never clean a DB without the dedicated test container marker.
            try (var marker = statement.executeQuery("SELECT marker FROM flyway_test_guard.guard")) {
                if (!marker.next() || !"animal-flyway-disposable".equals(marker.getString(1))) {
                    throw new IllegalStateException("Missing disposable database guard");
                }
            }
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
            statement.execute("DROP TABLE IF EXISTS migration_probe");
            // Reverse dependency order; fixed allowlist confined to this guarded test schema.
            for (String table : List.of("BATCH_JOB_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_STEP_EXECUTION_SEQ",
                    "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION",
                    "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE",
                    "chatbot_block_logs", "chatbot_messages", "chatbot_sessions", "outbox_events",
                    "processed_events", "sync_history", "animals", "shelters")) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    @Test
    void location_without_sql_cannot_report_migration_success() {
        assertThatThrownBy(() -> AnimalSchemaMigration.execute("migrate", settings,
                "classpath:com/pawbridge/animalservice/migration"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No reviewed migrations are packaged");
    }

    @Test
    void existing_schema_is_not_silently_baselined() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_probe (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO migration_probe VALUES (73)");
        }
        assertThatThrownBy(() -> AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id FROM migration_probe")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(73);
        }
    }

    @Test
    void initial_schema_matches_all_entities_and_initializes_batch_sequences() throws Exception {
        AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = 'pawbridge_animal'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(18); // 17 application/Batch tables + Flyway history.
            }
            for (String table : List.of("BATCH_JOB_SEQ", "BATCH_JOB_EXECUTION_SEQ", "BATCH_STEP_EXECUTION_SEQ")) {
                try (var rows = statement.executeQuery("SELECT ID, UNIQUE_KEY FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                    assertThat(rows.getString(2)).isEqualTo("0");
                    assertThat(rows.next()).isFalse();
                }
            }
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
            for (Class<?> entity : List.of(Animal.class, Shelter.class, SyncHistory.class,
                    ProcessedEvent.class, OutboxEvent.class, ChatbotSession.class,
                    ChatbotMessage.class, ChatbotBlockLog.class)) {
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
    void explicitly_baselined_existing_schema_preserves_rows_and_batch_counters() throws Exception {
        AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
            statement.execute("INSERT INTO processed_events VALUES ('existing-event', 'test', NOW(6))");
            statement.execute("UPDATE BATCH_JOB_SEQ SET ID = 73");
        }
        // Explicit baseline is rehearsed only here, on the guarded disposable database.
        AnimalSchemaMigration.configured(settings, "classpath:db/migration").baseline();
        AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration");
        AnimalSchemaMigration.execute("validate", settings, "classpath:db/migration");
        try (var connection = connection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT event_id FROM processed_events")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("existing-event");
            }
            try (var rows = statement.executeQuery("SELECT ID FROM BATCH_JOB_SEQ")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(73);
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(settings.url(), settings.username(), settings.password());
    }
}
