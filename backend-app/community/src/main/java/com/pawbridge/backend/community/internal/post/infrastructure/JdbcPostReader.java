package com.pawbridge.backend.community.internal.post.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostListReader;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPostReader implements PostReader, PostListReader {
    private static final String SELECT_POST = """
            SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at
            FROM pawbridge_community.posts
            WHERE post_id = ? AND deleted_at IS NULL
            """;
    private static final String SELECT_POSTS = """
            SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at
            FROM pawbridge_community.posts
            WHERE deleted_at IS NULL
            ORDER BY created_at DESC
            """;
    private static final TypeReference<List<String>> IMAGE_URLS_TYPE = new TypeReference<>() {};

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;

    public JdbcPostReader(JdbcOperations jdbc, TransactionOperations transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PostSnapshot> findById(long postId) {
        return transactions.execute(status ->
                jdbc.query(SELECT_POST, this::mapPost, postId).stream().findFirst());
    }

    @Override
    public List<PostSnapshot> findAll() {
        return transactions.execute(status -> jdbc.query(SELECT_POSTS, this::mapPost));
    }

    private PostSnapshot mapPost(ResultSet row, int rowNumber) throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return new PostSnapshot(
                row.getLong("post_id"),
                row.getLong("author_id"),
                row.getString("title"),
                row.getString("content"),
                row.getString("board_type"),
                readImageUrls(row.getString("image_urls")),
                row.getTimestamp("created_at", utc).toLocalDateTime(),
                row.getTimestamp("updated_at", utc).toLocalDateTime());
    }

    private List<String> readImageUrls(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, IMAGE_URLS_TYPE);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to read post image URLs", failure);
        }
    }
}
