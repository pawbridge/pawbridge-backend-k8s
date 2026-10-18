package com.pawbridge.backend.compatibility;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** Only the disposable, positively identified hosted-CI database may be mutated. */
final class F2PostgresqlFixture implements AutoCloseable {
    static final String APPLICATION_ROLE = "pawbridge_f2_reader";
    private static final String DATABASE = "pawbridge_f2_ci";
    private static final String MARKER = "pawbridge-f2-ci-only-v1";

    private final String jdbcUrl;
    private final String adminUser;
    private final String adminPassword;
    private final String applicationUser;
    private final String applicationPassword;
    private final Set<Long> postIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();

    private F2PostgresqlFixture(String jdbcUrl, String adminUser, String adminPassword,
            String applicationUser, String applicationPassword) {
        this.jdbcUrl = jdbcUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.applicationUser = applicationUser;
        this.applicationPassword = applicationPassword;
    }

    static F2PostgresqlFixture open() throws SQLException {
        String jdbcUrl = required("F2_TEST_JDBC_URL");
        require(jdbcUrl.startsWith("jdbc:postgresql://"), "F2 requires an explicit PostgreSQL URL");
        URI address = URI.create(jdbcUrl.substring("jdbc:".length()));
        require(Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(address.getHost()),
                "F2 database must use a loopback host");
        require(address.getPort() > 0 && address.getPort() <= 65535,
                "F2 database requires an explicit local port");
        require(("/" + DATABASE).equals(address.getPath()), "F2 database name is not allowlisted");
        require(address.getRawUserInfo() == null && address.getRawQuery() == null
                        && address.getRawFragment() == null,
                "F2 database URL must not contain credentials or options");
        F2PostgresqlFixture fixture = new F2PostgresqlFixture(jdbcUrl,
                required("F2_TEST_ADMIN_USER"), required("F2_TEST_ADMIN_PASSWORD"),
                required("F2_TEST_DB_USER"), required("F2_TEST_DB_PASSWORD"));
        require("postgres".equals(fixture.adminUser), "Unexpected F2 fixture administrator role");
        require(APPLICATION_ROLE.equals(fixture.applicationUser), "Unexpected F2 application role");
        try (Connection admin = fixture.adminConnection()) {
            // adminConnection verifies the database, role, PostgreSQL version, and fixture marker.
        }
        try (Connection application = DriverManager.getConnection(jdbcUrl,
                fixture.applicationUser, fixture.applicationPassword);
             Statement statement = application.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_database(), current_user, "
                     + "rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls "
                     + "FROM pg_roles WHERE rolname = current_user")) {
            require(row.next() && DATABASE.equals(row.getString(1))
                            && APPLICATION_ROLE.equals(row.getString(2)) && row.getBoolean(3)
                            && !row.getBoolean(4) && !row.getBoolean(5) && !row.getBoolean(6)
                            && !row.getBoolean(7) && !row.getBoolean(8) && !row.next(),
                    "F2 application role is not the restricted fixture role");
        }
        return fixture;
    }

    String jdbcUrl() { return jdbcUrl; }
    String applicationUser() { return applicationUser; }
    String applicationPassword() { return applicationPassword; }

    void insertUser(long userId, String nickname) throws SQLException {
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "INSERT INTO pawbridge_user.users (user_id, nickname) VALUES (?, ?)")) {
            statement.setLong(1, userId);
            statement.setString(2, nickname);
            statement.executeUpdate();
            userIds.add(userId);
        }
    }

    void insertPost(long postId, long authorId, String boardType, String imageUrls,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt)
            throws SQLException {
        insertPost("pawbridge_community", postId, authorId, "원본 제목", boardType,
                imageUrls, createdAt, updatedAt, deletedAt);
        postIds.add(postId);
    }

    void insertPublicDecoys(long postId, long authorId) throws SQLException {
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "INSERT INTO public.users (user_id, nickname) VALUES (?, ?)")) {
            statement.setLong(1, authorId);
            statement.setString(2, "잘못된 public 닉네임");
            statement.executeUpdate();
        }
        insertPost("public", postId, authorId, "잘못된 public 게시글", "REPORT", "[]",
                LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 1, 0, 0), null);
    }

    void revokeNicknameSelect() throws SQLException {
        executePermissionChange("REVOKE SELECT (nickname) ON pawbridge_user.users FROM "
                + APPLICATION_ROLE);
    }

    void restoreNicknameSelect() throws SQLException {
        executePermissionChange("GRANT SELECT (nickname) ON pawbridge_user.users TO "
                + APPLICATION_ROLE);
    }

    private void executePermissionChange(String sql) throws SQLException {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private void insertPost(String schema, long postId, long authorId, String title,
            String boardType, String imageUrls, LocalDateTime createdAt,
            LocalDateTime updatedAt, LocalDateTime deletedAt) throws SQLException {
        require(Set.of("pawbridge_community", "public").contains(schema), "Unexpected fixture schema");
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement("INSERT INTO " + schema
                     + ".posts (post_id, author_id, title, content, board_type, image_urls, "
                     + "created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?)")) {
            statement.setLong(1, postId);
            statement.setLong(2, authorId);
            statement.setString(3, title);
            statement.setString(4, "본문과 한글 이미지 주소");
            statement.setString(5, boardType);
            statement.setString(6, imageUrls);
            statement.setObject(7, createdAt);
            statement.setObject(8, updatedAt);
            statement.setObject(9, deletedAt);
            statement.executeUpdate();
        }
    }

    private Connection adminConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT current_database(), current_user, current_setting('server_version_num')")) {
            require(row.next() && DATABASE.equals(row.getString(1))
                            && "postgres".equals(row.getString(2))
                            && row.getInt(3) / 10000 == 17 && !row.next(),
                    "Unexpected F2 admin database, role, or PostgreSQL major version");
        } catch (RuntimeException | SQLException failure) {
            connection.close();
            throw failure;
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT value FROM pawbridge_f2_fixture.marker")) {
            require(row.next() && MARKER.equals(row.getString(1)) && !row.next(),
                    "F2 fixture marker is absent or ambiguous");
        } catch (RuntimeException | SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    @Override
    public void close() throws SQLException {
        try (Connection admin = adminConnection()) {
            for (String schema : Set.of("pawbridge_community", "public")) {
                try (PreparedStatement statement = admin.prepareStatement(
                        "DELETE FROM " + schema + ".posts WHERE post_id = ?")) {
                    for (long id : postIds) {
                        statement.setLong(1, id);
                        statement.executeUpdate();
                    }
                }
            }
            for (String schema : Set.of("pawbridge_user", "public")) {
                try (PreparedStatement statement = admin.prepareStatement(
                        "DELETE FROM " + schema + ".users WHERE user_id = ?")) {
                    for (long id : userIds) {
                        statement.setLong(1, id);
                        statement.executeUpdate();
                    }
                }
            }
        }
        postIds.clear();
        userIds.clear();
    }

    private static String required(String key) {
        String value = System.getenv(key);
        require(value != null && !value.isBlank(), "Required F2 fixture setting is missing: " + key);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
