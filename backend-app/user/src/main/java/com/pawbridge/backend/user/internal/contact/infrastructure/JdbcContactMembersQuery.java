package com.pawbridge.backend.user.internal.contact.infrastructure;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcContactMembersQuery implements ContactMembersQuery {
    private static final int MAX_MEMBERS_PER_REQUEST = 21;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcContactMembersQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public List<ContactMember> getAll(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_MEMBERS_PER_REQUEST
                || userIds.stream().anyMatch(userId -> userId == null || userId <= 0)) {
            throw new IllegalArgumentException("회원 조회 범위를 확인해 주세요.");
        }
        List<Long> distinctIds = List.copyOf(new LinkedHashSet<>(userIds));
        try {
            return readOnlyTransaction.execute(status -> {
                String placeholders = String.join(",", Collections.nCopies(distinctIds.size(), "?"));
                String sql = """
                        SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending
                        FROM pawbridge_user.users u
                        LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
                        WHERE u.user_id IN (%s)
                        """.formatted(placeholders);
                List<ContactMember> found = jdbc.query(sql, (row, rowNumber) -> {
                    boolean pending = row.getBoolean("pending");
                    return new ContactMember(row.getLong("user_id"),
                            pending ? "탈퇴한 회원" : row.getString("nickname"), !pending, pending);
                }, distinctIds.toArray());
                Map<Long, ContactMember> membersById = found.stream()
                        .collect(Collectors.toMap(ContactMember::userId, Function.identity()));
                return distinctIds.stream()
                        .map(userId -> membersById.getOrDefault(userId,
                                new ContactMember(userId, "탈퇴한 회원", false, false)))
                        .toList();
            });
        } catch (RuntimeException failure) {
            throw new ContactMemberUnavailableException(failure);
        }
    }
}
