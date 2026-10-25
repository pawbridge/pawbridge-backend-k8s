package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentListReader
import com.pawbridge.backend.community.internal.comment.application.CommentReader
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
import java.sql.ResultSet
import java.util.Calendar
import java.util.Optional
import java.util.TimeZone
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcCommentReader(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
) : CommentListReader, CommentReader {

    override fun findById(commentId: Long): Optional<CommentSnapshot> =
        transactions.execute {
            jdbc.query(SELECT_COMMENT, RowMapper { row, _ -> mapComment(row) }, commentId)
                .stream()
                .findFirst()
        }!!

    override fun findByPostId(postId: Long): List<CommentSnapshot> =
        transactions.execute {
            jdbc.query(SELECT_COMMENTS, RowMapper { row, _ -> mapComment(row) }, postId)
        }!!

    private fun mapComment(row: ResultSet): CommentSnapshot {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return CommentSnapshot(
            row.getLong("comment_id"),
            row.getLong("post_id"),
            row.getLong("author_id"),
            row.getString("content"),
            row.getTimestamp("created_at", utc).toLocalDateTime(),
            row.getTimestamp("updated_at", utc).toLocalDateTime(),
        )
    }

    private companion object {
        private val SELECT_COMMENT = """
            SELECT comment_id, post_id, author_id, content, created_at, updated_at
            FROM pawbridge_community.comments
            WHERE comment_id = ? AND deleted_at IS NULL
        """.trimIndent() + "\n"

        private val SELECT_COMMENTS = """
            SELECT comment_id, post_id, author_id, content, created_at, updated_at
            FROM pawbridge_community.comments
            WHERE post_id = ? AND deleted_at IS NULL
            ORDER BY created_at DESC
        """.trimIndent() + "\n"
    }
}
