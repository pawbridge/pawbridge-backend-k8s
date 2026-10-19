package com.pawbridge.backend.user.internal.contact.infrastructure;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcContactMemberQuery implements ContactMemberQuery {
    private static final String FIND_MEMBER_SQL = """
            SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcContactMemberQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public ContactMember get(long userId) {
        try {
            return readOnlyTransaction.execute(status -> jdbc.query(FIND_MEMBER_SQL, rows -> {
                if (!rows.next()) {
                    return new ContactMember(userId, "탈퇴한 회원", false, false);
                }
                boolean pending = rows.getBoolean("pending");
                return new ContactMember(rows.getLong("user_id"),
                        pending ? "탈퇴한 회원" : rows.getString("nickname"), !pending, pending);
            }, userId));
        } catch (RuntimeException failure) {
            throw new ContactMemberUnavailableException(failure);
        }
    }
}
