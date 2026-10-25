package com.pawbridge.backend.user.internal.passwordreset.infrastructure

import com.pawbridge.backend.user.internal.passwordreset.application.PasswordResetAccounts
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.OptionalLong
import java.util.TimeZone
import java.util.function.Supplier

class JdbcPasswordResetAccounts(
    private val jdbc: JdbcOperations,
    private val modifiedAt: Supplier<LocalDateTime>,
    private val sessions: UserSessionRevoker,
) : PasswordResetAccounts {

    constructor(jdbc: JdbcOperations) : this(jdbc, Supplier { LocalDateTime.now() })

    constructor(jdbc: JdbcOperations, modifiedAt: Supplier<LocalDateTime>) : this(jdbc, modifiedAt, UserSessionRevoker.NONE)

    override fun findLocalUserId(email: String?): OptionalLong {
        val found: List<Long> = jdbc.query(FIND_LOCAL, RowMapper { row, _ -> row.getLong("user_id") }, email, "LOCAL")
        if (found.size > 1) {
            throw IncorrectResultSizeDataAccessException(1, found.size)
        }
        return if (found.isEmpty()) OptionalLong.empty() else OptionalLong.of(found.first())
    }

    override fun updatePassword(userId: Long, encodedPassword: String?) {
        val now = modifiedAt.get()
        val changed = jdbc.update(PreparedStatementCreator { connection -> prepareUpdate(connection, userId, encodedPassword, now) })
        if (changed != 1) {
            throw IllegalStateException("Password reset affected an unexpected number of rows")
        }
        // Same transaction as the password write: a leaked refresh cookie stops working with the old password.
        sessions.revokeAll(userId)
    }

    private fun prepareUpdate(
        connection: Connection,
        userId: Long,
        encodedPassword: String?,
        now: LocalDateTime,
    ): PreparedStatement {
        val statement = connection.prepareStatement(UPDATE_PASSWORD)
        try {
            statement.setString(1, encodedPassword)
            statement.setTimestamp(2, Timestamp.valueOf(now), Calendar.getInstance(TimeZone.getTimeZone("UTC")))
            statement.setLong(3, userId)
            return statement
        } catch (failure: Exception) {
            try {
                statement.close()
            } catch (closing: SQLException) {
                failure.addSuppressed(closing)
            }
            throw failure
        }
    }

    private companion object {
        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val FIND_LOCAL = """
            SELECT user_id
            FROM pawbridge_user.users
            WHERE lower(email) = lower(?) AND lower(provider) = lower(?)
        """.trimIndent() + "\n"

        private val UPDATE_PASSWORD = """
            UPDATE pawbridge_user.users
            SET password = ?, updated_at = ?
            WHERE user_id = ?
        """.trimIndent() + "\n"
    }
}
