package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentModification
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
import com.pawbridge.backend.community.internal.comment.application.CommentWriter
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
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

class JdbcCommentWriter(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) : CommentWriter, CommentModification {

    constructor(jdbc: JdbcOperations, transactions: TransactionOperations) :
        this(jdbc, transactions, Clock.systemDefaultZone())

    override fun create(postId: Long, authorId: Long, content: String?): CommentSnapshot {
        try {
            return transactions.execute { write(postId, authorId, content) }!!
        } catch (failure: RuntimeException) {
            // The shared detector covers this schema's named rollback charset constraints,
            // including errors raised while the transaction is committing.
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw UnsupportedCommentCharactersException(failure)
            }
            throw failure
        }
    }

    override fun update(commentId: Long, content: String?) {
        try {
            transactions.execute(
                TransactionCallback<Any?> {
                    val updatedAt = LocalDateTime.now(clock)
                    // Ownership and dirty checking use the first snapshot; preserve any concurrent deletion.
                    val changed = jdbc.update(
                        PreparedStatementCreator { connection ->
                            prepareUpdate(connection, commentId, content, updatedAt)
                        },
                    )
                    if (changed != 1) {
                        throw IllegalStateException("Comment update affected an unexpected number of rows")
                    }
                    null
                },
            )
        } catch (failure: RuntimeException) {
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw UnsupportedCommentCharactersException(failure)
            }
            throw failure
        }
    }

    private fun write(postId: Long, authorId: Long, content: String?): CommentSnapshot {
        val now = LocalDateTime.now(clock)
        val commentId: Long? = jdbc.query(
            PreparedStatementCreator { connection -> prepare(connection, postId, authorId, content, now) },
            ResultSetExtractor { result ->
                if (!result.next()) {
                    throw IllegalStateException("Comment insert returned no identity")
                }
                result.getLong("comment_id")
            },
        )
        if (commentId == null) {
            throw IllegalStateException("Comment insert returned no identity")
        }
        return CommentSnapshot(commentId, postId, authorId, content, now, now)
    }

    private fun prepareUpdate(
        connection: Connection,
        commentId: Long,
        content: String?,
        updatedAt: LocalDateTime,
    ): PreparedStatement {
        val statement = connection.prepareStatement(UPDATE_COMMENT)
        try {
            statement.setString(1, content)
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            statement.setTimestamp(2, Timestamp.valueOf(updatedAt), utc)
            statement.setLong(3, commentId)
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

    private fun prepare(
        connection: Connection,
        postId: Long,
        authorId: Long,
        content: String?,
        now: LocalDateTime,
    ): PreparedStatement {
        val statement = connection.prepareStatement(INSERT_COMMENT)
        try {
            statement.setLong(1, postId)
            statement.setLong(2, authorId)
            statement.setString(3, content)
            // Match Hibernate's Timestamp.valueOf binding with its UTC JDBC calendar.
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val timestamp = Timestamp.valueOf(now)
            statement.setTimestamp(4, timestamp, utc)
            statement.setTimestamp(5, timestamp, utc)
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
        private val INSERT_COMMENT = """
            INSERT INTO pawbridge_community.comments
                (post_id, author_id, content, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            RETURNING comment_id
        """.trimIndent() + "\n"

        private val UPDATE_COMMENT = """
            UPDATE pawbridge_community.comments
            SET content = ?, updated_at = ?
            WHERE comment_id = ?
        """.trimIndent() + "\n"
    }
}
