package com.pawbridge.backend.user.internal.adminupdate.infrastructure

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.support.TransactionOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Supplier

class JdbcAdminUserUpdate(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
    private val modifiedAt: Supplier<LocalDateTime?>,
    private val sessions: UserSessionRevoker,
) : AdminUserUpdate {

    private val nicknames = JdbcUserNicknameStore(jdbc)

    constructor(jdbc: JdbcOperations, transaction: TransactionOperations) :
        this(jdbc, transaction, Supplier { LocalDateTime.now() })

    constructor(jdbc: JdbcOperations, transaction: TransactionOperations, modifiedAt: Supplier<LocalDateTime?>) :
        this(jdbc, transaction, modifiedAt, UserSessionRevoker.NONE)

    override fun update(userId: Long, changes: Changes) {
        transaction.executeWithoutResult { applyUpdate(userId, changes) }
    }

    private fun applyUpdate(userId: Long, changes: Changes) {
        val current = jdbc.query(
            SELECT_FOR_UPDATE,
            ResultSetExtractor { rows ->
                if (rows.next()) {
                    Current(
                        rows.getString("nickname"),
                        Role.valueOf(rows.getString("role")),
                        rows.getString("care_reg_no"),
                    )
                } else {
                    null
                }
            },
            userId,
        ) ?: throw UserNotFoundException()

        val requestedCareRegNo = changes.careRegNo
        if ((changes.role == Role.ROLE_SHELTER && current.role != Role.ROLE_SHELTER) ||
            (requestedCareRegNo != null && !isBlank(requestedCareRegNo) && requestedCareRegNo != current.careRegNo)
        ) {
            throw ShelterApprovalRequiredException()
        }

        var nickname = current.nickname
        val requestedNickname = changes.nickname
        // `!!` keeps the NullPointerException that `nickname.equals(...)` threw for a null stored nickname.
        if (requestedNickname != null && !isBlank(requestedNickname) && nickname!! != requestedNickname) {
            // Includes this same member: a case-only change can be a duplicate in the source.
            if (nicknames.existsByNickname(requestedNickname)) {
                throw NicknameDuplicateException()
            }
            nickname = requestedNickname
        }

        val role = changes.role ?: current.role
        val careRegNo = if (requestedCareRegNo == null || isBlank(requestedCareRegNo)) {
            current.careRegNo
        } else {
            requestedCareRegNo
        }

        // A managed JPA entity with no dirty property does not run auditing/UPDATE at flush.
        if (nickname!! == current.nickname && role == current.role && careRegNo == current.careRegNo) {
            return
        }

        val nextNickname = nickname
        val now: LocalDateTime? = modifiedAt.get()
        val changed = jdbc.update(
            PreparedStatementCreator { connection ->
                prepareUpdate(connection, userId, nextNickname, role, careRegNo, now)
            },
        )
        if (changed != 1) {
            throw IllegalStateException("Admin member update affected an unexpected row count")
        }
        if (isDemotion(current.role, role)) {
            // Same transaction as the role change: a demoted member cannot refresh into new access tokens.
            sessions.revokeAll(userId)
        }
    }

    private fun prepareUpdate(
        connection: Connection,
        userId: Long,
        nickname: String?,
        role: Role,
        careRegNo: String?,
        now: LocalDateTime?,
    ): PreparedStatement {
        val statement = connection.prepareStatement(UPDATE_USER)
        try {
            statement.setString(1, nickname)
            statement.setString(2, role.name)
            statement.setString(3, careRegNo)
            // Reuse the nickname writer's LocalDateTime auditing + Hibernate UTC binding contract.
            statement.setTimestamp(4, Timestamp.valueOf(now), Calendar.getInstance(TimeZone.getTimeZone("UTC")))
            statement.setLong(5, userId)
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

    private data class Current(
        val nickname: String?,
        val role: Role,
        val careRegNo: String?,
    )

    private companion object {
        // The trailing newline matches the former Java text blocks.
        private val SELECT_FOR_UPDATE = """
            SELECT user_id, nickname, role, care_reg_no FROM pawbridge_user.users
            WHERE user_id = ? FOR UPDATE
        """.trimIndent() + "\n"

        private val UPDATE_USER = """
            UPDATE pawbridge_user.users SET nickname = ?, role = ?, care_reg_no = ?, updated_at = ?
            WHERE user_id = ?
        """.trimIndent() + "\n"

        /**
         * A member loses privileges when moving down ADMIN > SHELTER > USER. Moving up (for example a new
         * administrator) keeps existing sessions.
         */
        private fun isDemotion(before: Role, after: Role): Boolean = rank(after) < rank(before)

        private fun rank(role: Role): Int = when (role) {
            Role.ROLE_USER -> 0
            Role.ROLE_SHELTER -> 1
            Role.ROLE_ADMIN -> 2
        }

        // Java String.isBlank semantics (Character.isWhitespace): Kotlin's isBlank would also treat NBSP,
        // U+2007 and U+202F as blank, so a nickname or registration number made of those would change meaning.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
    }
}
