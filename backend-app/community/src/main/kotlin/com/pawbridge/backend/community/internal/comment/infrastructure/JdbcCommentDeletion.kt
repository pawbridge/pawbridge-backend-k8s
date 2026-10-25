package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.domain.CommentOwnership
import com.pawbridge.backend.community.internal.post.infrastructure.PostgresqlRollbackCharsetViolation
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class JdbcCommentDeletion(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) : CommentDeletion {

    constructor(jdbc: JdbcOperations, transactions: TransactionOperations) :
        this(jdbc, transactions, Clock.systemDefaultZone())

    override fun delete(commentId: Long, authorId: Long) {
        try {
            transactions.execute(
                TransactionCallback<Any?> {
                    deleteInTransaction(commentId, authorId)
                    null
                },
            )
        } catch (failure: RuntimeException) {
            // The shared detector preserves the legacy advice's schema/constraint classification,
            // including a structured database rejection reported while committing.
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw UnsupportedCommentCharactersException(failure)
            }
            throw failure
        }
    }

    private fun deleteInTransaction(commentId: Long, authorId: Long) {
        val storedAuthor: Long? = jdbc.query(
            PreparedStatementCreator { connection -> prepare(connection, FIND_ACTIVE_COMMENT_AUTHOR, commentId) },
            ResultSetExtractor { row -> if (row.next()) row.getLong("author_id") else null },
        )
        if (storedAuthor == null) {
            throw CommentNotFoundException()
        }
        CommentOwnership.requireAuthor(storedAuthor, authorId)
        val deletedAt = LocalDateTime.now(clock)
        val updatedAt = LocalDateTime.now(clock)
        // Legacy permits two deletes that both observed an active comment to succeed.
        // Update only the dates so a concurrent content edit is not overwritten.
        val changed = jdbc.update(
            PreparedStatementCreator { connection ->
                prepare(connection, DELETE_COMMENT, deletedAt, updatedAt, commentId)
            },
        )
        if (changed != 1) {
            throw IllegalStateException("Comment deletion updated an unexpected number of rows")
        }
    }

    private fun prepare(connection: Connection, sql: String, vararg values: Any?): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        try {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            for (index in values.indices) {
                val value = values[index]
                if (value is LocalDateTime) {
                    statement.setTimestamp(index + 1, Timestamp.valueOf(value), utc)
                } else {
                    statement.setObject(index + 1, value)
                }
            }
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
        private val FIND_ACTIVE_COMMENT_AUTHOR = """
            SELECT author_id FROM pawbridge_community.comments
            WHERE comment_id = ? AND deleted_at IS NULL
        """.trimIndent() + "\n"

        private val DELETE_COMMENT = """
            UPDATE pawbridge_community.comments
            SET deleted_at = ?, updated_at = ?
            WHERE comment_id = ?
        """.trimIndent() + "\n"
    }
}
