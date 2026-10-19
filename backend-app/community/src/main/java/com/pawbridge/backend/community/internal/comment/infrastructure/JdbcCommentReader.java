package com.pawbridge.backend.community.internal.comment.infrastructure;

import com.pawbridge.backend.community.internal.comment.application.CommentListReader;
import com.pawbridge.backend.community.internal.comment.application.CommentReader;
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcCommentReader implements CommentListReader, CommentReader {
    private static final String SELECT_COMMENT = """
            SELECT comment_id, post_id, author_id, content, created_at, updated_at
            FROM pawbridge_community.comments
            WHERE comment_id = ? AND deleted_at IS NULL
            """;
    private static final String SELECT_COMMENTS = """
            SELECT comment_id, post_id, author_id, content, created_at, updated_at
            FROM pawbridge_community.comments
            WHERE post_id = ? AND deleted_at IS NULL
            ORDER BY created_at DESC
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;

    public JdbcCommentReader(JdbcOperations jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<CommentSnapshot> findById(long commentId) {
        return transactions.execute(status ->
                jdbc.query(SELECT_COMMENT, this::mapComment, commentId).stream().findFirst());
    }

    @Override
    public List<CommentSnapshot> findByPostId(long postId) {
        return transactions.execute(status -> jdbc.query(SELECT_COMMENTS, this::mapComment, postId));
    }

    private CommentSnapshot mapComment(ResultSet row, int rowNumber) throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return new CommentSnapshot(
                row.getLong("comment_id"),
                row.getLong("post_id"),
                row.getLong("author_id"),
                row.getString("content"),
                row.getTimestamp("created_at", utc).toLocalDateTime(),
                row.getTimestamp("updated_at", utc).toLocalDateTime());
    }
}
