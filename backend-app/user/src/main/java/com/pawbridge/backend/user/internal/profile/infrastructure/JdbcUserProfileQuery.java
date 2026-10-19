package com.pawbridge.backend.user.internal.profile.infrastructure;

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcUserProfileQuery implements UserProfileQuery {
    private static final String FIND_PROFILE_SQL = """
            SELECT user_id, email, name, nickname, provider, role, created_at
            FROM pawbridge_user.users
            WHERE user_id = ?
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcUserProfileQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public UserProfile require(long userId) {
        return readOnlyTransaction.execute(status -> jdbc.query(FIND_PROFILE_SQL, rows -> {
            if (!rows.next()) {
                throw new UserNotFoundException();
            }
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            Timestamp createdAt = rows.getTimestamp("created_at", utc);
            return new UserProfile(rows.getLong("user_id"), rows.getString("email"), rows.getString("name"),
                    rows.getString("nickname"), rows.getString("provider"), rows.getString("role"),
                    createdAt == null ? null : createdAt.toLocalDateTime());
        }, userId));
    }
}
