package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcPostReaderTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final ResultSet row = mock(ResultSet.class);
    private final JdbcPostReader reader = new JdbcPostReader(jdbc, transactions, new ObjectMapper());
    private final LocalDateTime createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000);
    private final LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000);
    private boolean transactionActive;

    @BeforeEach
    void prepareTransactionAndRow() throws Exception {
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<Object>>any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<Object> callback = invocation.getArgument(0);
                    transactionActive = true;
                    try {
                        return callback.doInTransaction(new SimpleTransactionStatus());
                    } finally {
                        transactionActive = false;
                    }
                });
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any(), eq(42L)))
                .thenAnswer(invocation -> {
                    assertTrue(transactionActive);
                    RowMapper<PostSnapshot> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        when(row.getLong("post_id")).thenReturn(42L);
        when(row.getLong("author_id")).thenReturn(7L);
        when(row.getString("title")).thenReturn("함께 찾아주세요");
        when(row.getString("content")).thenReturn("게시글 본문");
        when(row.getString("board_type")).thenReturn("REPORT");
        when(row.getString("image_urls")).thenReturn("[]");
        when(row.getTimestamp(eq("created_at"), any(Calendar.class))).thenAnswer(invocation -> {
            assertEquals("UTC", invocation.getArgument(1, Calendar.class).getTimeZone().getID());
            return Timestamp.valueOf(createdAt);
        });
        when(row.getTimestamp(eq("updated_at"), any(Calendar.class))).thenAnswer(invocation -> {
            assertEquals("UTC", invocation.getArgument(1, Calendar.class).getTimeZone().getID());
            return Timestamp.valueOf(updatedAt);
        });
    }

    @Test
    void given__post_row__when__find__then__snapshot_is_fully_mapped_before_transaction_returns() {
        PostSnapshot post = reader.findById(42L).orElseThrow();

        assertEquals(new PostSnapshot(
                42L, 7L, "함께 찾아주세요", "게시글 본문", "REPORT", List.of(), createdAt, updatedAt), post);
        assertFalse(transactionActive);
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    void given__stored_image_urls__when__find__then__legacy_null_order_and_duplicates_are_preserved(
            String stored, List<String> expected) throws Exception {
        when(row.getString("image_urls")).thenReturn(stored);

        assertEquals(expected, reader.findById(42L).orElseThrow().imageUrls());
        assertFalse(transactionActive);
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    void given__post_rows__when__find_all__then__existing_field_and_image_mapping_is_reused(
            String stored, List<String> expected) throws Exception {
        when(row.getString("image_urls")).thenReturn(stored);
        prepareListQuery();

        assertEquals(List.of(new PostSnapshot(
                42L, 7L, "함께 찾아주세요", "게시글 본문", "REPORT", expected, createdAt, updatedAt)),
                reader.findAll());
        assertFalse(transactionActive);
        verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any());
    }

    @Test
    void given__list_reader__when__nickname_is_queried__then__post_mapping_transaction_has_completed() throws Exception {
        prepareListQuery();
        ListPosts listPosts = new ListPosts(reader, authorId -> {
            assertFalse(transactionActive);
            assertEquals(7L, authorId);
            return Optional.of("반려친구");
        });

        assertEquals("반려친구", listPosts.read().getFirst().authorNickname());
        assertFalse(transactionActive);
    }

    @Test
    void given__no_post_rows__when__find_all__then__empty_list_is_returned_after_transaction() {
        doReturn(List.of()).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any());

        assertEquals(List.of(), reader.findAll());
        assertFalse(transactionActive);
    }

    @Test
    void given__invalid_list_image_json__when__find_all__then__mapping_failure_leaves_transaction() throws Exception {
        prepareListQuery();
        when(row.getString("image_urls")).thenReturn("{\"unexpected\":\"shape\"}");

        assertThrows(IllegalStateException.class, reader::findAll);
        assertFalse(transactionActive);
    }

    @Test
    void given__list_query_failure__when__find_all__then__original_failure_propagates_after_transaction() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        doThrow(failure).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any());

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, reader::findAll));
        assertFalse(transactionActive);
    }

    private void prepareListQuery() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any()))
                .thenAnswer(invocation -> {
                    assertTrue(transactionActive);
                    RowMapper<PostSnapshot> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    private static Stream<Arguments> imageUrls() {
        return Stream.of(
                Arguments.of(null, List.of()),
                Arguments.of("[]", List.of()),
                Arguments.of("null", null),
                Arguments.of("[\"https://example.test/한글.png\",null,\"https://example.test/한글.png\"]",
                        Arrays.asList("https://example.test/한글.png", null, "https://example.test/한글.png")));
    }

    @Test
    void given__no_post_row__when__find__then__empty_is_returned_after_transaction() {
        doReturn(List.of()).when(jdbc)
                .query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any(), eq(42L));

        assertEquals(Optional.empty(), reader.findById(42L));
        assertFalse(transactionActive);
    }

    @Test
    void given__invalid_image_json__when__find__then__mapping_failure_leaves_transaction() throws Exception {
        when(row.getString("image_urls")).thenReturn("{\"unexpected\":\"shape\"}");

        assertThrows(IllegalStateException.class, () -> reader.findById(42L));
        assertFalse(transactionActive);
    }

    @Test
    void given__query_failure__when__find__then__failure_propagates_after_transaction() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        doThrow(failure).when(jdbc)
                .query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any(), eq(42L));

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> reader.findById(42L)));
        assertFalse(transactionActive);
    }
}
