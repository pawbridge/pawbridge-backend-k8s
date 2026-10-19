package com.pawbridge.backend.community.internal.comment.infrastructure;

import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot;
import com.pawbridge.backend.community.internal.comment.application.CommentWriter;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
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

public final class JdbcCommentWriter implements CommentWriter {
    private static final String INSERT_COMMENT = """
            INSERT INTO pawbridge_community.comments
                (post_id, author_id, content, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            RETURNING comment_id
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JdbcCommentWriter(JdbcOperations jdbc, TransactionOperations transactions) {
        this(jdbc, transactions, Clock.systemDefaultZone());
    }

    public JdbcCommentWriter(JdbcOperations jdbc, TransactionOperations transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public CommentSnapshot create(long postId, long authorId, String content) {
        try {
            return transactions.execute(status -> write(postId, authorId, content));
        } catch (RuntimeException failure) {
            // The shared detector covers this schema's named rollback charset constraints,
            // including errors raised while the transaction is committing.
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw new UnsupportedCommentCharactersException(failure);
            }
            throw failure;
        }
    }

    private CommentSnapshot write(long postId, long authorId, String content) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long commentId = jdbc.query(connection -> prepare(connection, postId, authorId, content, now), result -> {
            if (!result.next()) {
                throw new IllegalStateException("Comment insert returned no identity");
            }
            return result.getLong("comment_id");
        });
        if (commentId == null) {
            throw new IllegalStateException("Comment insert returned no identity");
        }
        return new CommentSnapshot(commentId, postId, authorId, content, now, now);
    }

    private PreparedStatement prepare(Connection connection, long postId, long authorId,
                                      String content, LocalDateTime now) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(INSERT_COMMENT);
        try {
            statement.setLong(1, postId);
            statement.setLong(2, authorId);
            statement.setString(3, content);
            // Match Hibernate's Timestamp.valueOf binding with its UTC JDBC calendar.
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            Timestamp timestamp = Timestamp.valueOf(now);
            statement.setTimestamp(4, timestamp, utc);
            statement.setTimestamp(5, timestamp, utc);
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
