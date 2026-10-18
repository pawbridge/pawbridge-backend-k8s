package com.pawbridge.userservice.migration;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** Explicit, standalone PostgreSQL schema preparation; never starts the API or workers. */
public final class UserPostgresqlMigration {
    static final String SCHEMA = "pawbridge_user";
    static final String LOCATION = "classpath:db/postgresql";
    private static final Set<String> COMMANDS = Set.of("info", "validate", "migrate");

    private UserPostgresqlMigration() { }

    public static void main(String[] args) {
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("Expected exactly one command");
            }
            execute(args[0], Settings.from(System.getenv()));
        } catch (Exception failure) {
            // Database exception messages can include credentials and SQL values.
            System.err.println("PostgreSQL schema command failed ("
                    + failure.getClass().getSimpleName() + "). See the migration runbook.");
            System.exit(1);
        }
    }

    static Flyway configured(Settings settings) {
        return Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .locations(LOCATION)
                .createSchemas(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .ignoreMigrationPatterns(new String[0])
                .failOnMissingLocations(true)
                .loggers(new String[0])
                .load();
    }

    static void execute(String command, Settings settings) {
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Expected info, validate or migrate");
        }
        if (command.equals("migrate") && !settings.confirmed()) {
            throw new IllegalArgumentException("Explicit target confirmation is required");
        }
        Flyway flyway = configured(settings);
        if (flyway.info().all().length == 0) {
            throw new IllegalStateException("No reviewed PostgreSQL migrations are packaged");
        }
        switch (command) {
            case "info" -> {
                for (MigrationInfo migration : flyway.info().all()) {
                    System.out.println(migration.getVersion() + "\t" + migration.getState());
                }
            }
            case "validate" -> {
                flyway.validate();
                System.out.println("PostgreSQL migration history validated; data import is not verified.");
            }
            case "migrate" -> System.out.println("PostgreSQL migrations executed: "
                    + flyway.migrate().migrationsExecuted);
            default -> throw new IllegalArgumentException("Unsupported command");
        }
    }

    // Avoid generated toString exposing credentials. Separate names prevent MySQL env reuse.
    static final class Settings {
        private final String url;
        private final String username;
        private final String password;
        private final boolean confirmed;

        private Settings(String url, String username, String password, boolean confirmed) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.confirmed = confirmed;
        }

        static Settings from(Map<String, String> env) {
            String url = required(env, "USER_PG_MIGRATION_JDBC_URL");
            // Initial rehearsal runner deliberately permits loopback only, no JDBC options.
            if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/pawbridge")) {
                throw new IllegalArgumentException("Expected explicit local PostgreSQL pawbridge target");
            }
            URI target = URI.create(url.substring("jdbc:".length()));
            if (target.getPort() < 1 || target.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid PostgreSQL port");
            }
            return new Settings(url, required(env, "USER_PG_MIGRATION_USERNAME"),
                    required(env, "USER_PG_MIGRATION_PASSWORD"),
                    url.equals(env.get("USER_PG_MIGRATION_CONFIRM_TARGET")));
        }

        private static String required(Map<String, String> env, String name) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing " + name);
            }
            return value;
        }

        String url() { return url; }
        String username() { return username; }
        String password() { return password; }
        boolean confirmed() { return confirmed; }
    }
}
