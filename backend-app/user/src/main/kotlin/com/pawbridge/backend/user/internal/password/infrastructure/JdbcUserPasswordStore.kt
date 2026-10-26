package com.pawbridge.backend.user.internal.password.infrastructure

import com.pawbridge.backend.user.internal.password.application.StoredCredentials
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Optional
import java.util.TimeZone
import java.util.function.Supplier

class JdbcUserPasswordStore(
    private val jdbc: JdbcOperations,
    private val modifiedAt: Supplier<LocalDateTime>,
    private val sessions: UserSessionRevoker,
) : UserPasswordStore {

    constructor(jdbc: JdbcOperations) : this(jdbc, Supplier { LocalDateTime.now() })

    constructor(jdbc: JdbcOperations, modifiedAt: Supplier<LocalDateTime>) : this(jdbc, modifiedAt, UserSessionRevoker.NONE)

    override fun findCredentials(userId: Long): Optional<StoredCredentials> =
        jdbc.query(
            FIND_CREDENTIALS,
            ResultSetExtractor { rows ->
                if (rows.next()) {
                    Optional.of(StoredCredentials(rows.getString("provider"), rows.getString("password")))
                } else {
                    Optional.empty()
                }
            },
            userId,
        )!!

    override fun updatePassword(userId: Long, encodedPassword: String?) {
        val now = modifiedAt.get()
        val changed = jdbc.update(PreparedStatementCreator { connection -> prepareUpdate(connection, userId, encodedPassword, now) })
        if (changed != 1) {
            throw IllegalStateException("User password update affected an unexpected number of rows")
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
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            // Match legacy LocalDateTime auditing plus hibernate.jdbc.time_zone=UTC binding.
            statement.setTimestamp(2, Timestamp.valueOf(now), utc)
            statement.setLong(3, userId)
            return statement
        } catch (failure: Exception) {
            try {
                statement.close()
            } catch (closeFailure: SQLException) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private companion object {
        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val FIND_CREDENTIALS = """
            SELECT user_id, provider, password
            FROM pawbridge_user.users
            WHERE user_id = ?
        """.trimIndent() + "\n"

        private val UPDATE_PASSWORD = """
            UPDATE pawbridge_user.users
            SET password = ?, updated_at = ?
            WHERE user_id = ?
        """.trimIndent() + "\n"
    }
}
