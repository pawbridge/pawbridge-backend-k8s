package com.pawbridge.backend.user.internal.signup.infrastructure

import com.pawbridge.backend.user.internal.signup.application.SignedUp
import com.pawbridge.backend.user.internal.signup.application.SignupAccounts
import com.pawbridge.backend.user.internal.signup.application.SignupRole
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.support.GeneratedKeyHolder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Supplier

class JdbcSignupAccounts(
    private val jdbc: JdbcOperations,
    private val nicknames: Supplier<String?>,
    private val now: Supplier<LocalDateTime?>,
) : SignupAccounts {

    constructor(jdbc: JdbcOperations, nicknames: Supplier<String?>) :
        this(jdbc, nicknames, Supplier { LocalDateTime.now() })

    override fun existsLocalEmail(email: String?): Boolean {
        val exists = jdbc.queryForObject(EXISTS_LOCAL_EMAIL, Boolean::class.javaObjectType, email, "LOCAL")
        return exists == true
    }

    override fun createLocalUser(
        email: String?,
        name: String?,
        encodedPassword: String?,
        role: SignupRole,
        careRegNo: String?,
    ): SignedUp {
        // Nullable locals: a null from a Java supplier flows on exactly like the original (no early NPE).
        val nickname: String? = nicknames.get()
        try {
            return insert(email, name, encodedPassword, nickname, role, careRegNo)
        } catch (duplicate: DataIntegrityViolationException) {
            if (RollbackCharsetViolation.matches(duplicate)) {
                throw duplicate
            }
            // The original retries once with a new nickname inside the same transaction.
            LOG.warn("Signup nickname conflict, regenerating")
            return insert(email, name, encodedPassword, nicknames.get(), role, careRegNo)
        }
    }

    private fun insert(
        email: String?,
        name: String?,
        encodedPassword: String?,
        nickname: String?,
        role: SignupRole,
        careRegNo: String?,
    ): SignedUp {
        val timestamp: LocalDateTime? = now.get()
        val generated = GeneratedKeyHolder()
        jdbc.update(
            PreparedStatementCreator { connection ->
                prepareInsert(connection, email, name, encodedPassword, nickname, role, careRegNo, timestamp)
            },
            generated,
        )
        val id = generated.key
            ?: throw IllegalStateException("Signup account ID was not returned")
        return SignedUp(id.toLong(), email, name, nickname)
    }

    private fun prepareInsert(
        connection: Connection,
        email: String?,
        name: String?,
        encodedPassword: String?,
        nickname: String?,
        role: SignupRole,
        careRegNo: String?,
        timestamp: LocalDateTime?,
    ): PreparedStatement {
        val statement = connection.prepareStatement(INSERT_USER, arrayOf("user_id"))
        try {
            statement.setString(1, email)
            statement.setString(2, name)
            statement.setString(3, nickname)
            statement.setString(4, encodedPassword)
            statement.setString(5, role.name)
            statement.setString(6, careRegNo)
            statement.setString(7, "LOCAL")
            statement.setString(8, null)
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            statement.setTimestamp(9, Timestamp.valueOf(timestamp), utc)
            statement.setTimestamp(10, Timestamp.valueOf(timestamp), utc)
            return statement
        } catch (failure: Throwable) {
            try {
                statement.close()
            } catch (closing: SQLException) {
                failure.addSuppressed(closing)
            }
            throw failure
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(JdbcSignupAccounts::class.java)

        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val EXISTS_LOCAL_EMAIL = """
            SELECT (count(*) > 0)
            FROM pawbridge_user.users
            WHERE lower(email) = lower(?) AND lower(provider) = lower(?)
        """.trimIndent() + "\n"

        private const val INSERT_USER = "INSERT INTO pawbridge_user.users(email,name,nickname,password,role," +
            "care_reg_no,provider,provider_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)"
    }
}
