package com.pawbridge.backend.user.internal.passwordreset.infrastructure;

import com.pawbridge.backend.user.internal.passwordreset.application.PasswordResetAccounts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;

public final class JdbcPasswordResetAccounts implements PasswordResetAccounts {
    private static final String FIND_LOCAL = """
            SELECT user_id
            FROM pawbridge_user.users
            WHERE lower(email) = lower(?) AND lower(provider) = lower(?)
            """;
    private static final String UPDATE_PASSWORD = """
            UPDATE pawbridge_user.users
            SET password = ?, updated_at = ?
            WHERE user_id = ?
            """;

    private final JdbcOperations jdbc;
    private final Supplier<LocalDateTime> modifiedAt;

    public JdbcPasswordResetAccounts(JdbcOperations jdbc) {
        this(jdbc, LocalDateTime::now);
    }

    public JdbcPasswordResetAccounts(JdbcOperations jdbc, Supplier<LocalDateTime> modifiedAt) {
        this.jdbc = jdbc;
        this.modifiedAt = modifiedAt;
    }

    @Override
    public OptionalLong findLocalUserId(String email) {
        List<Long> found = jdbc.query(FIND_LOCAL, (row, n) -> row.getLong("user_id"), email, "LOCAL");
        if (found.size() > 1) {
            throw new IncorrectResultSizeDataAccessException(1, found.size());
        }
        return found.isEmpty() ? OptionalLong.empty() : OptionalLong.of(found.getFirst());
    }

    @Override
    public void updatePassword(long userId, String encodedPassword) {
        LocalDateTime now = modifiedAt.get();
        int changed = jdbc.update(connection -> prepareUpdate(connection, userId, encodedPassword, now));
        if (changed != 1) {
            throw new IllegalStateException("Password reset affected an unexpected number of rows");
        }
    }

    private PreparedStatement prepareUpdate(Connection connection, long userId, String encodedPassword,
            LocalDateTime now) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(UPDATE_PASSWORD);
        try {
            statement.setString(1, encodedPassword);
            statement.setTimestamp(2, Timestamp.valueOf(now), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.setLong(3, userId);
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
}
