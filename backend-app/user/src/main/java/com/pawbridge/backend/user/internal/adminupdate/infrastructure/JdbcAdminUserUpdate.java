package com.pawbridge.backend.user.internal.adminupdate.infrastructure;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException;
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcAdminUserUpdate implements AdminUserUpdate {
    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final JdbcUserNicknameStore nicknames;
    private final Supplier<LocalDateTime> modifiedAt;

    public JdbcAdminUserUpdate(JdbcOperations jdbc, TransactionOperations transaction) {
        this(jdbc, transaction, LocalDateTime::now);
    }

    public JdbcAdminUserUpdate(JdbcOperations jdbc, TransactionOperations transaction,
            Supplier<LocalDateTime> modifiedAt) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.nicknames = new JdbcUserNicknameStore(jdbc);
        this.modifiedAt = modifiedAt;
    }

    @Override
    public void update(long userId, Changes changes) {
        transaction.executeWithoutResult(status -> {
            Current current = jdbc.query("""
                    SELECT user_id, nickname, role, care_reg_no FROM pawbridge_user.users
                    WHERE user_id = ? FOR UPDATE
                    """, rows -> rows.next() ? new Current(rows.getString("nickname"),
                            Role.valueOf(rows.getString("role")), rows.getString("care_reg_no")) : null, userId);
            if (current == null) throw new UserNotFoundException();
            if ((changes.role() == Role.ROLE_SHELTER && current.role() != Role.ROLE_SHELTER)
                    || (changes.careRegNo() != null && !changes.careRegNo().isBlank()
                    && !changes.careRegNo().equals(current.careRegNo()))) {
                throw new ShelterApprovalRequiredException();
            }
            String nickname = current.nickname();
            if (changes.nickname() != null && !changes.nickname().isBlank()
                    && !nickname.equals(changes.nickname())) {
                // Includes this same member: a case-only change can be a duplicate in the source.
                if (nicknames.existsByNickname(changes.nickname())) throw new NicknameDuplicateException();
                nickname = changes.nickname();
            }
            Role role = changes.role() == null ? current.role() : changes.role();
            String careRegNo = changes.careRegNo() == null || changes.careRegNo().isBlank()
                    ? current.careRegNo() : changes.careRegNo();
            // A managed JPA entity with no dirty property does not run auditing/UPDATE at flush.
            if (nickname.equals(current.nickname()) && role == current.role()
                    && Objects.equals(careRegNo, current.careRegNo())) return;
            String nextNickname = nickname;
            LocalDateTime now = modifiedAt.get();
            int changed = jdbc.update(connection -> prepareUpdate(connection, userId, nextNickname, role, careRegNo, now));
            if (changed != 1) throw new IllegalStateException("Admin member update affected an unexpected row count");
        });
    }

    private PreparedStatement prepareUpdate(Connection connection, long userId, String nickname, Role role,
            String careRegNo, LocalDateTime now) throws SQLException {
        PreparedStatement statement = connection.prepareStatement("""
                UPDATE pawbridge_user.users SET nickname = ?, role = ?, care_reg_no = ?, updated_at = ?
                WHERE user_id = ?
                """);
        try {
            statement.setString(1, nickname);
            statement.setString(2, role.name());
            statement.setString(3, careRegNo);
            // Reuse the nickname writer's LocalDateTime auditing + Hibernate UTC binding contract.
            statement.setTimestamp(4, Timestamp.valueOf(now), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.setLong(5, userId);
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private record Current(String nickname, Role role, String careRegNo) { }
}
