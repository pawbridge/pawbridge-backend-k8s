package com.pawbridge.backend.community.internal.comment.infrastructure;

import com.pawbridge.backend.community.internal.comment.application.CommentDeletion;
import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.domain.CommentOwnership;
import com.pawbridge.backend.community.internal.post.infrastructure.PostgresqlRollbackCharsetViolation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcCommentDeletion implements CommentDeletion {
    private static final String FIND_ACTIVE_COMMENT_AUTHOR = """
            SELECT author_id FROM pawbridge_community.comments
            WHERE comment_id = ? AND deleted_at IS NULL
            """;
    private static final String DELETE_COMMENT = """
            UPDATE pawbridge_community.comments
            SET deleted_at = ?, updated_at = ?
            WHERE comment_id = ?
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JdbcCommentDeletion(JdbcOperations jdbc, TransactionOperations transactions) {
        this(jdbc, transactions, Clock.systemDefaultZone());
    }

    public JdbcCommentDeletion(JdbcOperations jdbc, TransactionOperations transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void delete(long commentId, long authorId) {
        try {
            transactions.execute(status -> {
                deleteInTransaction(commentId, authorId);
                return null;
            });
        } catch (RuntimeException failure) {
            // The shared detector preserves the legacy advice's schema/constraint classification,
            // including a structured database rejection reported while committing.
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw new UnsupportedCommentCharactersException(failure);
            }
            throw failure;
        }
    }

    private void deleteInTransaction(long commentId, long authorId) {
        Long storedAuthor = jdbc.query(connection -> prepare(connection, FIND_ACTIVE_COMMENT_AUTHOR, commentId),
                row -> row.next() ? row.getLong("author_id") : null);
        if (storedAuthor == null) {
            throw new CommentNotFoundException();
        }
        CommentOwnership.requireAuthor(storedAuthor, authorId);
        LocalDateTime deletedAt = LocalDateTime.now(clock);
        LocalDateTime updatedAt = LocalDateTime.now(clock);
        // Legacy permits two deletes that both observed an active comment to succeed.
        // Update only the dates so a concurrent content edit is not overwritten.
        int changed = jdbc.update(connection -> prepare(connection, DELETE_COMMENT, deletedAt, updatedAt, commentId));
        if (changed != 1) {
            throw new IllegalStateException("Comment deletion updated an unexpected number of rows");
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof LocalDateTime time) {
                    statement.setTimestamp(index + 1, Timestamp.valueOf(time), utc);
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
