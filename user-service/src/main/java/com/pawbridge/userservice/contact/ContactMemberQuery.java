package com.pawbridge.userservice.contact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("postgresql")
public class ContactMemberQuery {
    private static final int MAX_MEMBERS_PER_REQUEST = 21;

    private final JdbcTemplate jdbc;

    public ContactMemberQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ContactMember get(long userId) {
        return getAll(List.of(userId)).get(0);
    }

    public List<ContactMember> getAll(List<Long> userIds) {
        validateIds(userIds);
        List<Long> distinctIds = List.copyOf(new LinkedHashSet<>(userIds));
        String placeholders = String.join(",", java.util.Collections.nCopies(distinctIds.size(), "?"));
        String sql = """
                SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending
                FROM pawbridge_user.users u
                LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
                WHERE u.user_id IN (%s)
                """.formatted(placeholders);

        List<ContactMember> found = jdbc.query(sql, (row, rowNumber) -> {
            boolean deletionPending = row.getBoolean("pending");
            String nickname = deletionPending ? "탈퇴한 회원" : row.getString("nickname");
            return new ContactMember(row.getLong("user_id"), nickname, !deletionPending, deletionPending);
        }, distinctIds.toArray());
        Map<Long, ContactMember> membersById = found.stream()
                .collect(Collectors.toMap(ContactMember::userId, Function.identity()));

        return distinctIds.stream()
                .map(userId -> membersById.getOrDefault(userId, ContactMember.missing(userId)))
                .toList();
    }

    private static void validateIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_MEMBERS_PER_REQUEST
                || userIds.stream().anyMatch(userId -> userId == null || userId <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회원 조회 범위를 확인해 주세요.");
        }
    }
}
