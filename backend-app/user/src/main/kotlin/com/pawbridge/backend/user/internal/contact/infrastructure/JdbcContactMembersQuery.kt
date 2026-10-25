package com.pawbridge.backend.user.internal.contact.infrastructure

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcContactMembersQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : ContactMembersQuery {

    override fun getAll(userIds: List<Long?>?): List<ContactMember> {
        if (userIds == null ||
            userIds.isEmpty() ||
            userIds.size > MAX_MEMBERS_PER_REQUEST ||
            userIds.any { userId -> userId == null || userId <= 0 }
        ) {
            throw IllegalArgumentException("회원 조회 범위를 확인해 주세요.")
        }
        val distinctIds: List<Long> = userIds.filterNotNull().distinct()
        try {
            return readOnlyTransaction.execute { _ ->
                val placeholders = distinctIds.joinToString(",") { "?" }
                // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
                val sql = """
                    SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending
                    FROM pawbridge_user.users u
                    LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
                    WHERE u.user_id IN (%s)
                """.trimIndent().format(placeholders) + "\n"
                val found: List<ContactMember> = jdbc.query(
                    sql,
                    RowMapper { row, _ ->
                        val pending = row.getBoolean("pending")
                        ContactMember(
                            row.getLong("user_id"),
                            if (pending) WITHDRAWN_NICKNAME else row.getString("nickname"),
                            !pending,
                            pending,
                        )
                    },
                    *distinctIds.toTypedArray(),
                )
                val membersById = found.associateBy { member -> member.userId }
                distinctIds.map { userId ->
                    membersById[userId] ?: ContactMember(userId, WITHDRAWN_NICKNAME, false, false)
                }
            }!!
        } catch (failure: RuntimeException) {
            throw ContactMemberUnavailableException(failure)
        }
    }

    private companion object {
        private const val MAX_MEMBERS_PER_REQUEST = 21
        private const val WITHDRAWN_NICKNAME = "탈퇴한 회원"
    }
}
