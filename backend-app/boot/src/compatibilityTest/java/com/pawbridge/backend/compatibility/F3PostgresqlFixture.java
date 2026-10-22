package com.pawbridge.backend.compatibility;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds owner checks to F2's same single disposable database, never another PostgreSQL. */
final class F3PostgresqlFixture implements AutoCloseable {
    static final String WRITER = "pawbridge_f3_writer";
    static final long AUTHOR = 9_300_003L;
    private final F2PostgresqlFixture reads;
    private final String owner;

    private F3PostgresqlFixture(F2PostgresqlFixture reads, String owner) {
        this.reads = reads;
        this.owner = owner;
    }

    static F3PostgresqlFixture open() throws SQLException {
        require("true".equals(System.getenv("GITHUB_ACTIONS"))
                && "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                && "Linux".equals(System.getenv("RUNNER_OS"))
                && "pawbridge/pawbridge-backend-k8s".equals(System.getenv("GITHUB_REPOSITORY")),
                "F3 compatibility execution requires the repository's GitHub-hosted Linux runner");
        String owner = required("GITHUB_RUN_ID") + "-" + required("GITHUB_RUN_ATTEMPT");
        require(owner.matches("[0-9]+-[0-9]+") && owner.equals(required("F3_CI_OWNER")),
                "F3 compatibility owner mismatch");
        require(WRITER.equals(required("F3_TEST_WRITER_USER")), "Unexpected F3 writer");
        F3PostgresqlFixture fixture = new F3PostgresqlFixture(F2PostgresqlFixture.open(), owner);
        try (Connection ignored = fixture.admin()) { }
        return fixture;
    }

    F2PostgresqlFixture reads() { return reads; }
    String writerPassword() { return required("F3_TEST_WRITER_PASSWORD"); }

    Connection admin() throws SQLException {
        Connection connection = DriverManager.getConnection(reads.jdbcUrl(),
                required("F2_TEST_ADMIN_USER"), required("F2_TEST_ADMIN_PASSWORD"));
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT current_database(),current_user,current_setting('server_version_num'),value,owner "
                + "FROM pawbridge_f3_fixture.marker")) {
            require(row.next() && "pawbridge_f2_ci".equals(row.getString(1))
                    && "postgres".equals(row.getString(2)) && row.getInt(3) == 170011
                    && "pawbridge-f3-ci-only-v1".equals(row.getString(4))
                    && owner.equals(row.getString(5)) && !row.next(), "F3 fixture identity mismatch");
        } catch (RuntimeException | SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    void execute(String sql) throws SQLException {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    long count(String table) throws SQLException {
        require(java.util.Set.of("posts", "outbox_events", "post_search_documents").contains(table),
                "Unexpected F3 fixture table");
        try (Connection connection = admin(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT count(*) FROM pawbridge_community." + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** Outbox rows are observed as the fixture admin: the candidate writer holds no outbox privileges. */
    long outboxEvents(String aggregateId, String type) throws SQLException {
        String sql = "SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id=?"
                + (type == null ? "" : " AND type=?");
        try (Connection connection = admin(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, aggregateId);
            if (type != null) statement.setString(2, type);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    @Override public void close() throws SQLException {
        // Only the legacy oracle writes outbox rows now; the candidate never does. Clean them before their posts.
        execute("DELETE FROM pawbridge_community.outbox_events WHERE aggregate_type='Post' AND aggregate_id IN "
                + "(SELECT post_id::text FROM pawbridge_community.posts WHERE author_id=" + AUTHOR + "); "
                + "DELETE FROM pawbridge_community.posts WHERE author_id=" + AUTHOR);
        reads.close();
    }

    static String required(String key) {
        String value = System.getenv(key);
        require(value != null && !value.isBlank(), "Required F3 fixture setting is missing: " + key);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
