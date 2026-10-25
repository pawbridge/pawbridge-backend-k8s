package com.pawbridge.backend.community.internal.post.infrastructure

import com.pawbridge.backend.community.internal.post.application.PostDeletion
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.domain.PostOwnership
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

class JdbcPostDeletion(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) : PostDeletion {

    constructor(jdbc: JdbcOperations, transactions: TransactionOperations) :
        this(jdbc, transactions, Clock.systemDefaultZone())

    override fun delete(postId: Long, authorId: Long) {
        transactions.execute(
            TransactionCallback<Any?> {
                deleteInTransaction(postId, authorId)
                null
            },
        )
    }

    private fun deleteInTransaction(postId: Long, authorId: Long) {
        val storedAuthor: Long? = jdbc.query(
            PreparedStatementCreator { connection -> prepare(connection, LOCK_POST, postId) },
            ResultSetExtractor { row -> if (row.next()) row.getLong("author_id") else null },
        )
        if (storedAuthor == null) {
            throw PostNotFoundException()
        }
        PostOwnership.requireAuthor(storedAuthor, authorId)
        val deletedAt = LocalDateTime.now(clock)
        // Legacy Post.delete() and JPA auditing set local wall-clock values, bound with the UTC JDBC calendar.
        val updatedAt = LocalDateTime.now(clock)
        val changed = jdbc.update(
            PreparedStatementCreator { connection ->
                prepare(connection, DELETE_POST, deletedAt, updatedAt, postId, authorId)
            },
        )
        if (changed != 1) {
            throw PostNotFoundException()
        }
        // A soft delete does not trigger the search table's ON DELETE CASCADE.
        jdbc.update(PreparedStatementCreator { connection -> prepare(connection, DELETE_SEARCH, postId) })
    }

    private fun prepare(connection: Connection, sql: String, vararg values: Any?): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        try {
            for (index in values.indices) {
                val value = values[index]
                if (value is LocalDateTime) {
                    statement.setTimestamp(
                        index + 1,
                        Timestamp.valueOf(value),
                        Calendar.getInstance(TimeZone.getTimeZone("UTC")),
                    )
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
        private val LOCK_POST = """
            SELECT author_id FROM pawbridge_community.posts
            WHERE post_id = ? AND deleted_at IS NULL
            FOR UPDATE
        """.trimIndent() + "\n"

        private val DELETE_POST = """
            UPDATE pawbridge_community.posts
            SET deleted_at = ?, updated_at = ?
            WHERE post_id = ? AND author_id = ? AND deleted_at IS NULL
        """.trimIndent() + "\n"

        private const val DELETE_SEARCH =
            "DELETE FROM pawbridge_community.post_search_documents WHERE post_id = ?"
    }
}
