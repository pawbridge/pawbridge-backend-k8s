package com.pawbridge.backend.community.internal.post.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostListReader;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.community.internal.post.application.PostPageReader;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPostReader implements PostReader, PostListReader, PostPageReader {
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

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "postId", "post_id", "authorId", "author_id", "title", "title", "content", "content",
            "boardType", "board_type", "imageUrls", "image_urls", "deletedAt", "deleted_at",
            "createdAt", "created_at", "updatedAt", "updated_at");

    @Override
    public PostPageReader.Slice findPage(PostPageReader.Request request) {
        String order = request.orders().stream().map(this::sortExpression).collect(Collectors.joining(", "));
        String sql = "SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at "
                + "FROM pawbridge_community.posts WHERE deleted_at IS NULL"
                + (order.isEmpty() ? "" : " ORDER BY " + order) + " LIMIT ? OFFSET ?";
        return transactions.execute(status -> {
            // The original JPA PageableUtils rejects offsets outside setFirstResult's integer range.
            if (request.offset() > Integer.MAX_VALUE) {
                throw new org.springframework.dao.InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)");
            }
            List<PostSnapshot> posts = jdbc.query(sql, this::mapPost, request.size(), request.offset());
            // Match Spring Data PageableExecutionUtils: a nonempty partial page supplies its own total.
            long total = posts.size() < request.size() && (request.number() == 0 || !posts.isEmpty())
                    ? request.offset() + posts.size()
                    : jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.posts WHERE deleted_at IS NULL", Long.class);
            return new PostPageReader.Slice(posts, total);
        });
    }

    private String sortExpression(PostPageReader.Order order) {
        String property = java.beans.Introspector.decapitalize(order.property());
        String column = SORT_COLUMNS.get(property);
        if (column == null) throw new IllegalArgumentException("Unknown post sort property");
        if (order.ignoreCase() && (property.equals("title") || property.equals("content"))) column = "lower(" + column + ")";
        return column + (order.ascending() ? " ASC" : " DESC");
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
