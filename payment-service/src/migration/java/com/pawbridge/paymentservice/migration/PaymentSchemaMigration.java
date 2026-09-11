package com.pawbridge.paymentservice.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** A standalone process: no Spring context, scheduled jobs, Kafka, Redis, or API server. */
public final class PaymentSchemaMigration {
    static final String SCHEMA = "pawbridge_payment";
    private static final Set<String> COMMANDS = Set.of("inspect", "info", "validate", "migrate");

    private PaymentSchemaMigration() { }

    public static void main(String[] args) {
        try {
            String command = command(args);
            Settings settings = Settings.from(System.getenv());
            if (command.equals("inspect")) {
                inspect(settings);
            } else {
                execute(command, settings, "classpath:db/migration");
            }
        } catch (Exception failure) {
            // Driver/Flyway exception messages may contain SQL, connection data or credentials.
            System.err.println("Schema command failed (" + failure.getClass().getSimpleName()
                    + "). Check the runbook, target access, schema inventory and migration files.");
            System.exit(1);
        }
    }

    static String command(String[] args) {
        if (args.length != 1 || !COMMANDS.contains(args[0])) {
            throw new IllegalArgumentException("Expected inspect, info, validate or migrate");
        }
        return args[0];
    }

    static Flyway configured(Settings settings, String location) {
        return Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .locations(location)
                .createSchemas(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .ignoreMigrationPatterns(new String[0])
                .failOnMissingLocations(true)
                // Use no logger backends; "none" is not a Flyway 10 logger name.
                .loggers(new String[0])
                .load();
    }

    static void execute(String command, Settings settings, String location) {
        if (!Set.of("info", "validate", "migrate").contains(command)) {
            throw new IllegalArgumentException("Unsupported Flyway command");
        }
        if (command.equals("migrate") && !settings.confirmed()) {
            throw new IllegalArgumentException("Explicit target confirmation is required");
        }
        Flyway flyway = configured(settings, location);
        MigrationInfo[] migrations = flyway.info().all();
        if (!command.equals("info") && flyway.info().pending().length == 0 && migrations.length == 0) {
            throw new IllegalStateException("No reviewed migrations are packaged");
        }
        switch (command) {
            case "info" -> {
                for (MigrationInfo migration : migrations) {
                    System.out.println(migration.getVersion() + "\t" + migration.getState());
                }
                System.out.println("Migration entries: " + migrations.length);
            }
            case "validate" -> {
                flyway.validate();
                System.out.println("Migration history validated; live DDL equivalence is not checked.");
            }
            case "migrate" -> System.out.println("Migrations executed: " + flyway.migrate().migrationsExecuted);
            default -> throw new IllegalArgumentException("Unsupported command");
        }
    }

    private static void inspect(Settings settings) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                settings.url(), settings.username(), settings.password())) {
            connection.setReadOnly(true);
            if (!SCHEMA.equals(connection.getCatalog())) {
                throw new IllegalStateException("Unexpected database");
            }
            var tables = new ArrayList<String>();
            try (var statement = connection.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
                statement.setString(1, SCHEMA);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                }
            }
            for (String table : tables) {
                // Quote database-owned identifiers, including literal backticks.
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SHOW CREATE TABLE `"
                             + table.replace("`", "``") + "`")) {
                    if (result.next()) {
                        System.out.println(result.getString(2) + ";\n");
                    }
                }
            }
            System.out.println("-- Base tables inspected: " + tables.size()
                    + ". No row data exported. Views, triggers and routines require separate review.");
        }
    }

    // Deliberately not a record: generated toString must never expose the password.
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
            String url = required(env, "PAYMENT_MIGRATION_JDBC_URL");
            // No userinfo, query credentials, multi-host URLs or cross-service database names.
            if (!url.matches("jdbc:mysql://[a-zA-Z0-9.-]+:[0-9]{1,5}/pawbridge_payment")) {
                throw new IllegalArgumentException("Expected one explicit MySQL host, port and payment schema");
            }
            return new Settings(url, required(env, "PAYMENT_MIGRATION_USERNAME"),
                    required(env, "PAYMENT_MIGRATION_PASSWORD"),
                    url.equals(env.get("PAYMENT_MIGRATION_CONFIRM_TARGET")));
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
