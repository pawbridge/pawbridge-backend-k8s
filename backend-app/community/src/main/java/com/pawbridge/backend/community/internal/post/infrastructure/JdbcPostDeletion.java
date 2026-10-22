package com.pawbridge.backend.community.internal.post.infrastructure;

import com.pawbridge.backend.community.internal.post.application.PostDeletion;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.domain.PostOwnership;
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

public final class JdbcPostDeletion implements PostDeletion {
    private static final String LOCK_POST = """
            SELECT author_id FROM pawbridge_community.posts
            WHERE post_id = ? AND deleted_at IS NULL
            FOR UPDATE
            """;
    private static final String DELETE_POST = """
            UPDATE pawbridge_community.posts
            SET deleted_at = ?, updated_at = ?
            WHERE post_id = ? AND author_id = ? AND deleted_at IS NULL
            """;
    private static final String DELETE_SEARCH =
            "DELETE FROM pawbridge_community.post_search_documents WHERE post_id = ?";

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JdbcPostDeletion(JdbcOperations jdbc, TransactionOperations transactions) {
        this(jdbc, transactions, Clock.systemDefaultZone());
    }

    public JdbcPostDeletion(JdbcOperations jdbc, TransactionOperations transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void delete(long postId, long authorId) {
        transactions.execute(status -> {
            deleteInTransaction(postId, authorId);
            return null;
        });
    }

    private void deleteInTransaction(long postId, long authorId) {
        Long storedAuthor = jdbc.query(connection -> prepare(connection, LOCK_POST, postId),
                row -> row.next() ? row.getLong("author_id") : null);
        if (storedAuthor == null) {
            throw new PostNotFoundException();
        }
        PostOwnership.requireAuthor(storedAuthor, authorId);
        LocalDateTime deletedAt = LocalDateTime.now(clock);
        // Legacy Post.delete() and JPA auditing set local wall-clock values, bound with the UTC JDBC calendar.
        LocalDateTime updatedAt = LocalDateTime.now(clock);
        int changed = jdbc.update(connection -> prepare(connection, DELETE_POST, deletedAt, updatedAt, postId, authorId));
        if (changed != 1) {
            throw new PostNotFoundException();
        }
        // A soft delete does not trigger the search table's ON DELETE CASCADE.
        jdbc.update(connection -> prepare(connection, DELETE_SEARCH, postId));
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof LocalDateTime time) {
                    statement.setTimestamp(index + 1, Timestamp.valueOf(time),
                            Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
