package com.pawbridge.backend.community.internal.post.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.application.PostWriter;
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPostWriter implements PostWriter, AutoCloseable {
    private static final String INSERT_POST = """
            INSERT INTO pawbridge_community.posts
                (author_id, title, content, board_type, image_urls, created_at, updated_at)
            VALUES (?, ?, ?, ?, CAST(? AS json), ?, ?)
            RETURNING post_id
            """;
    private static final String INSERT_OUTBOX = """
            INSERT INTO pawbridge_community.outbox_events
                (event_id, aggregate_type, aggregate_id, type, payload, created_at)
            VALUES (?, ?, ?, ?, CAST(? AS json), ?)
            """;
    private static final String INSERT_SEARCH_DOCUMENT = """
            INSERT INTO pawbridge_community.post_search_documents
                (post_id, title_hash, content_hash, analyzer_version, tokens)
            VALUES (?, md5(?), md5(?), ?,
                setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', ?), 'B'))
            ON CONFLICT (post_id) DO UPDATE SET title_hash = excluded.title_hash,
                content_hash = excluded.content_hash, analyzer_version = excluded.analyzer_version,
                tokens = excluded.tokens
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final KoreanSearchTerms terms = new KoreanSearchTerms();

    public JdbcPostWriter(JdbcOperations jdbc, TransactionOperations transactions, ObjectMapper objectMapper) {
        this(jdbc, transactions, objectMapper, Clock.systemDefaultZone());
    }

    public JdbcPostWriter(JdbcOperations jdbc, TransactionOperations transactions,
                          ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public PostSnapshot create(PostToCreate post) {
        try {
            return transactions.execute(status -> write(post));
        } catch (RuntimeException failure) {
            // Includes structured constraint failures reported while committing.
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw new UnsupportedPostCharactersException(failure);
            }
            throw failure;
        }
    }

    private PostSnapshot write(PostToCreate post) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long postId = jdbc.query(connection -> prepare(connection, INSERT_POST,
                post.authorId(), post.title(), post.content(), post.boardType().name(),
                json(post.imageUrls()), now, now), result -> {
                    if (!result.next()) {
                        throw new IllegalStateException("Post insert returned no identity");
                    }
                    return result.getLong("post_id");
                });
        if (postId == null) {
            throw new IllegalStateException("Post insert returned no identity");
        }
        String payload = json(Map.of(
                "postId", postId, "authorId", post.authorId(), "title", post.title(),
                "content", post.content(), "boardType", post.boardType().name(), "imageUrls", post.imageUrls()));
        jdbc.update(connection -> prepare(connection, INSERT_OUTBOX,
                UUID.randomUUID().toString(), "Post", Long.toString(postId), "POST_CREATED", payload,
                LocalDateTime.now(clock)));
        jdbc.update(connection -> prepare(connection, INSERT_SEARCH_DOCUMENT,
                postId, post.title(), post.content(), KoreanSearchTerms.VERSION,
                terms.document(post.title()), terms.document(post.content())));
        return new PostSnapshot(postId, post.authorId(), post.title(), post.content(), post.boardType().name(),
                post.imageUrls(), now, now);
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof LocalDateTime time) {
                    // Hibernate's TIMESTAMP binding uses Timestamp.valueOf plus its UTC JDBC calendar.
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize post data", failure);
        }
    }

    @Override
    public void close() {
        terms.close();
    }
}
