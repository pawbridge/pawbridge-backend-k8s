package com.pawbridge.backend.community.internal.post.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostSearchReader;
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPostSearchReader implements PostSearchReader, AutoCloseable {
    private static final String SEARCH_POSTS = """
            WITH readiness AS (
                SELECT NOT EXISTS(
                    SELECT 1 FROM pawbridge_community.posts p
                    LEFT JOIN pawbridge_community.post_search_documents d ON d.post_id = p.post_id
                    WHERE p.deleted_at IS NULL AND (d.post_id IS NULL OR d.title_hash <> md5(p.title)
                        OR d.content_hash <> md5(p.content) OR d.analyzer_version <> ?)) AS ready
            ), ranked AS (
                SELECT p.post_id, p.author_id, p.title, p.content, p.board_type, p.image_urls,
                    p.created_at, p.updated_at, ts_rank(d.tokens, to_tsquery('simple', ?)) AS score
                FROM pawbridge_community.posts p
                JOIN pawbridge_community.post_search_documents d ON d.post_id = p.post_id
                WHERE p.deleted_at IS NULL AND d.tokens @@ to_tsquery('simple', ?)
                ORDER BY score DESC, p.created_at DESC, p.post_id DESC LIMIT 10
            )
            SELECT readiness.ready, ranked.* FROM readiness LEFT JOIN ranked ON readiness.ready
            ORDER BY score DESC, created_at DESC, post_id DESC
            """;
    private static final TypeReference<List<String>> IMAGE_URLS_TYPE = new TypeReference<>() {};
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;
    private final KoreanSearchTerms terms = new KoreanSearchTerms();

    public JdbcPostSearchReader(JdbcOperations jdbc, TransactionOperations transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PostSnapshot> search(String keyword) {
        String query = terms.query(keyword);
        if (query.isEmpty()) {
            return List.of();
        }
        try {
            return transactions.execute(status -> jdbc.query(
                    SEARCH_POSTS, this::mapPost, KoreanSearchTerms.VERSION, query, query)
                    .stream().filter(Objects::nonNull).toList());
        } catch (DataAccessException | CannotCreateTransactionException | TransactionTimedOutException failure) {
            throw new PostSearchUnavailableException(failure);
        }
    }

    private PostSnapshot mapPost(ResultSet row, int rowNumber) throws SQLException {
        if (!row.getBoolean("ready")) {
            throw new PostSearchUnavailableException();
        }
        if (row.getObject("post_id") == null) {
            return null;
        }
        return new PostSnapshot(
                row.getLong("post_id"), row.getLong("author_id"), row.getString("title"), row.getString("content"),
                PostBoardType.valueOf(row.getString("board_type")).name(),
                readImageUrls(row.getString("image_urls")),
                row.getObject("created_at", LocalDateTime.class), row.getObject("updated_at", LocalDateTime.class));
    }

    private List<String> readImageUrls(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, IMAGE_URLS_TYPE);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored post image list", failure);
        }
    }

    @Override
    public void close() {
        terms.close();
    }
}
