package com.pawbridge.animalservice.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgresql")
class AnimalPostgresqlMigrationDatabaseTest {
    private AnimalPostgresqlMigration.Settings settings;

    @BeforeEach
    void prepare_guarded_disposable_schema() throws SQLException {
        String port = System.getenv("ANIMAL_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Set ANIMAL_PG_MIGRATION_TEST_PORT for the disposable database");
        }
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        Map<String, String> env = AnimalPostgresqlMigrationTest.environment(url);
        env.put("ANIMAL_PG_MIGRATION_CONFIRM_TARGET", url);
        settings = AnimalPostgresqlMigration.Settings.from(env);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            // No deletion until the dedicated local test marker has been checked.
            try (ResultSet rows = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
                if (!rows.next() || !"animal-pg-disposable".equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("Missing disposable PostgreSQL guard");
                }
            }
            statement.execute("DROP SCHEMA IF EXISTS pawbridge_animal CASCADE");
            statement.execute("CREATE SCHEMA pawbridge_animal");
        }
    }

    @Test
    void complete_schema_can_be_applied_repeatedly_without_changing_existing_rows() throws SQLException {
        migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO processed_events VALUES ('kept', 'test', TIMESTAMP '2026-09-19 10:11:12.123456')");
        }
        migrate();
        AnimalPostgresqlMigration.execute("validate", settings);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='pawbridge_animal' AND table_type='BASE TABLE'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(34); // Includes animal/shelter search projections.
            }
            try (ResultSet rows = statement.executeQuery("SELECT processed_at FROM processed_events WHERE event_id='kept'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, LocalDateTime.class)).isEqualTo(LocalDateTime.parse("2026-09-19T10:11:12.123456"));
            }
            try (ResultSet rows = statement.executeQuery("SELECT nextval('batch_job_seq'),nextval('batch_job_execution_seq'),nextval('batch_step_execution_seq')")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
                assertThat(rows.getLong(2)).isEqualTo(1);
                assertThat(rows.getLong(3)).isEqualTo(1);
            }
        }
    }

    @Test
    void nonempty_schema_cannot_be_silently_adopted() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE existing_data (id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO existing_data VALUES (73)");
        }
        assertThatThrownBy(this::migrate).isInstanceOf(FlywayException.class);
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM existing_data")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(73);
        }
    }

    @Test
    void embeddings_keep_versions_and_optional_focus_but_reject_legacy_dimensions() throws SQLException {
        migrate();
        long animal = seedAnimal();
        insertEmbedding(animal, "dinov3-test-v1", 1024);
        insertEmbedding(animal, "dinov3-test-v2", 1024);
        assertThatThrownBy(() -> insertEmbedding(animal, "dinov2-legacy", 384))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertEmbedding(animal, "dinov3-test-v1", 1024))
                .isInstanceOf(SQLException.class); // same photo/model must update, not duplicate.
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*),min(public.vector_dims(image_vector)) FROM animal_image_embeddings WHERE animal_vector IS NULL")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(2);
            assertThat(rows.getInt(2)).isEqualTo(1024);
        }
    }

    @Test
    void embedding_color_payload_and_animal_ownership_are_preserved() throws SQLException {
        migrate();
        long animal = seedAnimal();
        insertEmbedding(animal, "dinov3-test-v1", 1024);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("UPDATE animal_image_embeddings SET coat_color='{}'::jsonb"))
                    .isInstanceOf(SQLException.class);
            statement.execute("UPDATE animal_image_embeddings SET coat_color_version='test-v1',coat_color='{\"marginal\":[0.1,0.2],\"joint\":[0.3]}'::jsonb");
            try (ResultSet rows = statement.executeQuery("SELECT coat_color->'marginal'->>1 FROM animal_image_embeddings")) {
                rows.next();assertThat(rows.getString(1)).isEqualTo("0.2");
            }
            statement.execute("DELETE FROM animals WHERE id=" + animal);
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM animal_image_embeddings")) {
                rows.next();assertThat(rows.getInt(1)).isZero();
            }
        }
        assertThatThrownBy(() -> insertEmbedding(animal, "dinov3-test-v2", 1024)).isInstanceOf(SQLException.class);
    }

    @Test
    void missing_pgvector_rolls_back_vector_migration_without_reporting_success() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP EXTENSION vector"); // dedicated guarded database, no CASCADE
            try {
                assertThatThrownBy(this::migrate).isInstanceOf(FlywayException.class);
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    rows.next();assertThat(rows.getInt(1)).isEqualTo(2);
                }
                try (ResultSet rows = statement.executeQuery("SELECT to_regclass('animal_image_embeddings')")) {
                    rows.next();assertThat(rows.getString(1)).isNull();
                }
            } finally {
                statement.execute("CREATE EXTENSION vector WITH SCHEMA public");
            }
        }
        migrate();
        AnimalPostgresqlMigration.execute("validate", settings);
    }

    @Test
    void missing_text_extension_stops_search_migration_without_rewriting_existing_history() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP EXTENSION pg_trgm");
            try {
                assertThatThrownBy(this::migrate).isInstanceOf(FlywayException.class);
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    rows.next();assertThat(rows.getInt(1)).isEqualTo(3);
                }
            } finally {
                statement.execute("CREATE EXTENSION pg_trgm WITH SCHEMA public");
            }
        }
        migrate();
        AnimalPostgresqlMigration.execute("validate", settings);
    }

    private void migrate() { AnimalPostgresqlMigration.execute("migrate", settings); }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(settings.url(), settings.username(), settings.password());
        connection.setSchema("pawbridge_animal");
        return connection;
    }

    private long seedAnimal() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO shelters(created_at,care_reg_no,name) VALUES (TIMESTAMP '2026-09-19 10:00:00','test-shelter','테스트 보호소')");
            try (ResultSet rows = statement.executeQuery("INSERT INTO animals(created_at,api_source,apms_notice_no,favorite_count,gender,neuter_status,notice_end_date,notice_start_date,species,status,shelter_id) SELECT TIMESTAMP '2026-09-19 10:00:00','APMS_ANIMAL','test-notice',0,'UNKNOWN','UNKNOWN',DATE '2026-09-30',DATE '2026-09-19','DOG','PROTECT',id FROM shelters RETURNING id")) {
                rows.next();return rows.getLong(1);
            }
        }
    }

    private void insertEmbedding(long animal, String version, int dimensions) throws SQLException {
        String vector = "[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO animal_image_embeddings(animal_id,source_sha256,model_version,image_vector,focus_status) VALUES (?, ?, ?, ?::public.vector, 'no_focus')")) {
            statement.setLong(1,animal);statement.setString(2,"a".repeat(64));
            statement.setString(3,version);statement.setString(4,vector);statement.executeUpdate();
        }
    }
}
