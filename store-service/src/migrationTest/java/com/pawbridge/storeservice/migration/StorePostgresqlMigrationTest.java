package com.pawbridge.storeservice.migration;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.api.configuration.Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorePostgresqlMigrationTest {
    static Map<String, String> environment(String url) {
        return new HashMap<>(Map.of("STORE_PG_MIGRATION_JDBC_URL", url,
                "STORE_PG_MIGRATION_USERNAME", "postgres",
                "STORE_PG_MIGRATION_PASSWORD", "local_pg_test_only"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://localhost:3306/pawbridge_store",
            "jdbc:postgresql://localhost:5432/pawbridge_wrong",
            "jdbc:postgresql://localhost:5432/pawbridge?password=secret",
            "jdbc:postgresql://store:secret@localhost:5432/pawbridge",
            "jdbc:postgresql://remote:5432/pawbridge",
            "jdbc:postgresql://localhost:0/pawbridge",
            "jdbc:postgresql://localhost:65536/pawbridge"})
    void unintended_targets_are_rejected_without_echoing_credentials(String url) {
        assertThatThrownBy(() -> StorePostgresqlMigration.Settings.from(environment(url)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"clean", "repair", "baseline", "undo", "inspect"})
    void unsupported_commands_fail_before_database_access(String command) {
        StorePostgresqlMigration.Settings settings = StorePostgresqlMigration.Settings.from(
                environment("jdbc:postgresql://127.0.0.1:1/pawbridge"));
        assertThatThrownBy(() -> StorePostgresqlMigration.execute(command, settings))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void migrate_requires_exact_target_confirmation_before_connecting() {
        Map<String, String> env = environment("jdbc:postgresql://127.0.0.1:1/pawbridge");
        env.put("STORE_PG_MIGRATION_CONFIRM_TARGET", "jdbc:postgresql://127.0.0.1:2/pawbridge");
        StorePostgresqlMigration.Settings settings = StorePostgresqlMigration.Settings.from(env);
        assertThatThrownBy(() -> StorePostgresqlMigration.execute("migrate", settings))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirmation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STORE_PG_MIGRATION_JDBC_URL", "STORE_PG_MIGRATION_USERNAME",
            "STORE_PG_MIGRATION_PASSWORD"})
    void missing_input_fails_before_connecting(String name) {
        Map<String, String> env = environment("jdbc:postgresql://localhost:5432/pawbridge");
        env.remove(name);
        assertThatThrownBy(() -> StorePostgresqlMigration.Settings.from(env))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postgresql_history_is_isolated_from_mysql_and_automatic_adoption_is_disabled() {
        Map<String, String> env = environment("jdbc:postgresql://localhost:5432/pawbridge");
        env.put("STORE_PG_MIGRATION_CONFIRM_TARGET", env.get("STORE_PG_MIGRATION_JDBC_URL"));
        StorePostgresqlMigration.Settings settings = StorePostgresqlMigration.Settings.from(env);
        Configuration config = StorePostgresqlMigration.configured(settings).getConfiguration();
        assertThat(settings.confirmed()).isTrue();
        assertThat(settings.toString()).doesNotContain("local_pg_test_only");
        assertThat(config.getLocations()).extracting(Object::toString).containsExactly("classpath:db/postgresql");
        assertThat(config.getSchemas()).containsExactly("pawbridge_store");
        assertThat(config.isCleanDisabled()).isTrue();
        assertThat(config.isCreateSchemas()).isFalse();
        assertThat(config.isBaselineOnMigrate()).isFalse();
        assertThat(config.isOutOfOrder()).isFalse();
        assertThat(config.isValidateOnMigrate()).isTrue();
        assertThat(config.getIgnoreMigrationPatterns()).isEmpty();
    }
}
