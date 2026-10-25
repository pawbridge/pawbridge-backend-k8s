package com.pawbridge.backend.user.internal.nickname.infrastructure

import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore
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

class JdbcUserNicknameStore(
    private val jdbc: JdbcOperations,
    private val modifiedAt: Supplier<LocalDateTime>,
) : UserNicknameStore {

    constructor(jdbc: JdbcOperations) : this(jdbc, Supplier { LocalDateTime.now() })

    override fun findNickname(userId: Long): Optional<String> =
        jdbc.query(
            FIND_NICKNAME,
            ResultSetExtractor { rows ->
                if (rows.next()) {
                    Optional.of(rows.getString("nickname"))
                } else {
                    Optional.empty()
                }
            },
            userId,
        )!!

    override fun existsByNickname(nickname: String?): Boolean =
        jdbc.queryForObject(EXISTS_NICKNAME, Boolean::class.javaObjectType, nickname)!!

    override fun updateNickname(userId: Long, nickname: String?) {
        val now = modifiedAt.get()
        val changed = jdbc.update(PreparedStatementCreator { connection -> prepareUpdate(connection, userId, nickname, now) })
        if (changed != 1) {
            throw IllegalStateException("User nickname update affected an unexpected number of rows")
        }
    }

    private fun prepareUpdate(
        connection: Connection,
        userId: Long,
        nickname: String?,
        now: LocalDateTime,
    ): PreparedStatement {
        val statement = connection.prepareStatement(UPDATE_NICKNAME)
        try {
            statement.setString(1, nickname)
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
        private val FIND_NICKNAME = """
            SELECT user_id, nickname
            FROM pawbridge_user.users
            WHERE user_id = ?
        """.trimIndent() + "\n"

        private val EXISTS_NICKNAME = """
            SELECT (count(*) > 0)
            FROM pawbridge_user.users
            WHERE lower(nickname) = lower(?)
        """.trimIndent() + "\n"

        private val UPDATE_NICKNAME = """
            UPDATE pawbridge_user.users
            SET nickname = ?, updated_at = ?
            WHERE user_id = ?
        """.trimIndent() + "\n"
    }
}
