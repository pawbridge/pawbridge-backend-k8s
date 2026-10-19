package com.pawbridge.backend.user.internal.nickname.infrastructure;

import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore;
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

public final class JdbcUserNicknameStore implements UserNicknameStore {
    private static final String FIND_NICKNAME = """
            SELECT user_id, nickname
            FROM pawbridge_user.users
            WHERE user_id = ?
            """;
    private static final String EXISTS_NICKNAME = """
            SELECT (count(*) > 0)
            FROM pawbridge_user.users
            WHERE lower(nickname) = lower(?)
            """;
    private static final String UPDATE_NICKNAME = """
            UPDATE pawbridge_user.users
            SET nickname = ?, updated_at = ?
            WHERE user_id = ?
            """;

    private final JdbcOperations jdbc;
    private final Supplier<LocalDateTime> modifiedAt;

    public JdbcUserNicknameStore(JdbcOperations jdbc) {
        this(jdbc, LocalDateTime::now);
    }

    public JdbcUserNicknameStore(JdbcOperations jdbc, Supplier<LocalDateTime> modifiedAt) {
        this.jdbc = jdbc;
        this.modifiedAt = modifiedAt;
    }

    @Override
    public Optional<String> findNickname(long userId) {
        return jdbc.query(FIND_NICKNAME, rows -> rows.next()
                ? Optional.of(rows.getString("nickname")) : Optional.empty(), userId);
    }

    @Override
    public boolean existsByNickname(String nickname) {
        return jdbc.queryForObject(EXISTS_NICKNAME, Boolean.class, nickname);
    }

    @Override
    public void updateNickname(long userId, String nickname) {
        LocalDateTime now = modifiedAt.get();
        int changed = jdbc.update(connection -> prepareUpdate(connection, userId, nickname, now));
        if (changed != 1) {
            throw new IllegalStateException("User nickname update affected an unexpected number of rows");
        }
    }

    private PreparedStatement prepareUpdate(Connection connection, long userId, String nickname, LocalDateTime now)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(UPDATE_NICKNAME);
        try {
            statement.setString(1, nickname);
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
