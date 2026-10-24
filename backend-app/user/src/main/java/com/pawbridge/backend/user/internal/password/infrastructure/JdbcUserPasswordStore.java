package com.pawbridge.backend.user.internal.password.infrastructure;

import com.pawbridge.backend.user.internal.password.application.StoredCredentials;
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore;
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcOperations;

public final class JdbcUserPasswordStore implements UserPasswordStore {
    private static final String FIND_CREDENTIALS = """
            SELECT user_id, provider, password
            FROM pawbridge_user.users
            WHERE user_id = ?
            """;
    private static final String UPDATE_PASSWORD = """
            UPDATE pawbridge_user.users
            SET password = ?, updated_at = ?
            WHERE user_id = ?
            """;

    private final JdbcOperations jdbc;
    private final Supplier<LocalDateTime> modifiedAt;
    private final UserSessionRevoker sessions;

    public JdbcUserPasswordStore(JdbcOperations jdbc) {
        this(jdbc, LocalDateTime::now);
    }

    public JdbcUserPasswordStore(JdbcOperations jdbc, Supplier<LocalDateTime> modifiedAt) {
        this(jdbc, modifiedAt, UserSessionRevoker.NONE);
    }

    public JdbcUserPasswordStore(JdbcOperations jdbc, Supplier<LocalDateTime> modifiedAt, UserSessionRevoker sessions) {
        this.jdbc = jdbc;
        this.modifiedAt = modifiedAt;
        this.sessions = sessions;
    }

    @Override
    public Optional<StoredCredentials> findCredentials(long userId) {
        return jdbc.query(FIND_CREDENTIALS, rows -> rows.next()
                ? Optional.of(new StoredCredentials(rows.getString("provider"), rows.getString("password")))
                : Optional.empty(), userId);
    }

    @Override
    public void updatePassword(long userId, String encodedPassword) {
        LocalDateTime now = modifiedAt.get();
        int changed = jdbc.update(connection -> prepareUpdate(connection, userId, encodedPassword, now));
        if (changed != 1) {
            throw new IllegalStateException("User password update affected an unexpected number of rows");
        }
        // Same transaction as the password write: a leaked refresh cookie stops working with the old password.
        sessions.revokeAll(userId);
    }

    private PreparedStatement prepareUpdate(Connection connection, long userId, String encodedPassword, LocalDateTime now)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(UPDATE_PASSWORD);
        try {
            statement.setString(1, encodedPassword);
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            // Match legacy LocalDateTime auditing plus hibernate.jdbc.time_zone=UTC binding.
            statement.setTimestamp(2, Timestamp.valueOf(now), utc);
            statement.setLong(3, userId);
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
