package com.pawbridge.animalservice.migration;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnimalSchemaMigrationTest {
    static Map<String, String> environment(String url) {
        return new HashMap<>(Map.of("ANIMAL_MIGRATION_JDBC_URL", url,
                "ANIMAL_MIGRATION_USERNAME", "migration_test",
                "ANIMAL_MIGRATION_PASSWORD", "local_test_only"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"clean", "repair", "baseline", "undo", ""})
    void unsupported_commands_are_rejected(String command) {
        assertThatThrownBy(() -> AnimalSchemaMigration.command(new String[]{command}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extra_arguments_cannot_override_safety() {
        assertThatThrownBy(() -> AnimalSchemaMigration.command(new String[]{"migrate", "-baselineOnMigrate=true"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://localhost:3306/pawbridge_store",
            "jdbc:mysql://localhost:3306/pawbridge_animal?password=secret",
            "jdbc:mysql://user:secret@localhost:3306/pawbridge_animal",
            "jdbc:mysql://first:3306,second:3306/pawbridge_animal"})
    void unintended_targets_are_rejected(String url) {
        assertThatThrownBy(() -> AnimalSchemaMigration.Settings.from(environment(url)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANIMAL_MIGRATION_JDBC_URL", "ANIMAL_MIGRATION_USERNAME", "ANIMAL_MIGRATION_PASSWORD"})
    void missing_input_fails_before_database_access(String name) {
        var env = environment("jdbc:mysql://localhost:3306/pawbridge_animal");
        env.remove(name);
        assertThatThrownBy(() -> AnimalSchemaMigration.Settings.from(env)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmation_must_match_entire_target_url() {
        var env = environment("jdbc:mysql://localhost:3306/pawbridge_animal");
        env.put("ANIMAL_MIGRATION_CONFIRM_TARGET", "jdbc:mysql://another:3306/pawbridge_animal");
        var settings = AnimalSchemaMigration.Settings.from(env);
        assertThat(settings.confirmed()).isFalse();
        assertThatThrownBy(() -> AnimalSchemaMigration.execute("migrate", settings, "classpath:db/migration"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runner_preserves_safety_configuration() {
        var env = environment("jdbc:mysql://localhost:3306/pawbridge_animal");
        env.put("ANIMAL_MIGRATION_CONFIRM_TARGET", env.get("ANIMAL_MIGRATION_JDBC_URL"));
        var settings = AnimalSchemaMigration.Settings.from(env);
        var config = AnimalSchemaMigration.configured(settings, "classpath:db/migration").getConfiguration();
        assertThat(settings.confirmed()).isTrue();
        assertThat(settings.toString()).doesNotContain("local_test_only");
        assertThat(config.isCleanDisabled()).isTrue();
        assertThat(config.isBaselineOnMigrate()).isFalse();
        assertThat(config.isCreateSchemas()).isFalse();
        assertThat(config.isOutOfOrder()).isFalse();
        assertThat(config.isValidateOnMigrate()).isTrue();
        assertThat(config.isValidateMigrationNaming()).isTrue();
        assertThat(config.getIgnoreMigrationPatterns()).isEmpty();
        assertThat(config.getLoggers()).isEmpty();
        assertThat(config.getSchemas()).containsExactly("pawbridge_animal");
        assertThat(config.getDefaultSchema()).isEqualTo("pawbridge_animal");
    }
}
