package com.pawbridge.backend.user.internal.profile.infrastructure

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.support.TransactionOperations
import java.util.Calendar
import java.util.TimeZone

class JdbcUserProfileQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : UserProfileQuery {

    override fun require(userId: Long): UserProfile =
        readOnlyTransaction.execute { _ ->
            jdbc.query(
                FIND_PROFILE_SQL,
                ResultSetExtractor { rows ->
                    if (!rows.next()) {
                        throw UserNotFoundException()
                    }
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    val createdAt = rows.getTimestamp("created_at", utc)
                    UserProfile(
                        rows.getLong("user_id"),
                        rows.getString("email"),
                        rows.getString("name"),
                        rows.getString("nickname"),
                        rows.getString("provider"),
                        rows.getString("role"),
                        createdAt?.toLocalDateTime(),
                    )
                },
                userId,
            )
        }!!

    private companion object {
        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val FIND_PROFILE_SQL = """
            SELECT user_id, email, name, nickname, provider, role, created_at
            FROM pawbridge_user.users
            WHERE user_id = ?
        """.trimIndent() + "\n"
    }
}
