package com.pawbridge.backend.user.internal.contact.infrastructure

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.support.TransactionOperations

class JdbcContactMemberQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : ContactMemberQuery {

    override fun get(userId: Long): ContactMember {
        try {
            return readOnlyTransaction.execute { _ ->
                jdbc.query(
                    FIND_MEMBER_SQL,
                    ResultSetExtractor { rows ->
                        if (!rows.next()) {
                            ContactMember(userId, WITHDRAWN_NICKNAME, false, false)
                        } else {
                            val pending = rows.getBoolean("pending")
                            ContactMember(
                                rows.getLong("user_id"),
                                if (pending) WITHDRAWN_NICKNAME else rows.getString("nickname"),
                                !pending,
                                pending,
                            )
                        }
                    },
                    userId,
                )
            }!!
        } catch (failure: RuntimeException) {
            throw ContactMemberUnavailableException(failure)
        }
    }

    private companion object {
        private const val WITHDRAWN_NICKNAME = "탈퇴한 회원"

        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val FIND_MEMBER_SQL = """
            SELECT u.user_id, u.nickname, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
    }
}
