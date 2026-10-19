package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException;
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.application.SearchPosts;
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcPostSearchReaderTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final ResultSet row = mock(ResultSet.class);
    private final JdbcPostSearchReader reader = new JdbcPostSearchReader(jdbc, transactions, new ObjectMapper());
    private final LocalDateTime createdAt = LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000);
    private final LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000);
    private boolean transactionActive;

    @BeforeEach
    void prepareTransactionAndRow() throws Exception {
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<Object>>any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            transactionActive = true;
            try { return callback.doInTransaction(new SimpleTransactionStatus()); }
            finally { transactionActive = false; }
        });
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any(),
                eq(KoreanSearchTerms.VERSION), anyString(), anyString())).thenAnswer(invocation -> {
                    assertTrue(transactionActive);
                    assertEquals("'alpha' | 'beta'", invocation.getArgument(3));
                    assertEquals(invocation.getArgument(3, String.class), invocation.getArgument(4));
                    RowMapper<PostSnapshot> mapper = invocation.getArgument(1);
                    return Collections.singletonList(mapper.mapRow(row, 0));
                });
        when(row.getBoolean("ready")).thenReturn(true);
        when(row.getObject("post_id")).thenReturn(42L);
        when(row.getLong("post_id")).thenReturn(42L);
        when(row.getLong("author_id")).thenReturn(7L);
        when(row.getString("title")).thenReturn("검색 제목");
        when(row.getString("content")).thenReturn("검색 본문");
        when(row.getString("board_type")).thenReturn("REPORT");
        when(row.getString("image_urls")).thenReturn("[]");
        when(row.getObject("created_at", LocalDateTime.class)).thenReturn(createdAt);
        when(row.getObject("updated_at", LocalDateTime.class)).thenReturn(updatedAt);
    }

    @AfterEach
    void closeReaderAnalyzer() { reader.close(); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "!!!"})
    void given__empty_query__when__search__then__skip_transaction_and_database(String keyword) {
        assertEquals(List.of(), reader.search(keyword));
        verifyNoInteractions(transactions, jdbc);
    }

    @Test
    void given__invalid_input__when__search__then__reject_before_transaction_or_database() {
        assertThrows(InvalidPostSearchInputException.class, () -> reader.search("x".repeat(201)));
        verifyNoInteractions(transactions, jdbc);
    }

    @ParameterizedTest
    @MethodSource("imageUrls")
    void given__ready_row__when__search__then__map_legacy_search_fields_and_local_date_time(
            String stored, List<String> expected) throws Exception {
        when(row.getString("image_urls")).thenReturn(stored);

        assertEquals(List.of(new PostSnapshot(42L, 7L, "검색 제목", "검색 본문", "REPORT",
                expected, createdAt, updatedAt)), reader.search("alpha alpha beta"));
        assertFalse(transactionActive);
        verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any(),
                eq(KoreanSearchTerms.VERSION), anyString(), anyString());
    }

    private static Stream<Arguments> imageUrls() {
        return Stream.of(Arguments.of(null, List.of()), Arguments.of("[]", List.of()),
                Arguments.of("null", null),
                Arguments.of("[\"한글\",null,\"한글\"]", Arrays.asList("한글", null, "한글")));
    }

    @Test
    void given__unready_projection__when__search__then__whole_result_is_unavailable() throws Exception {
        when(row.getBoolean("ready")).thenReturn(false);

        assertThrows(PostSearchUnavailableException.class, () -> reader.search("alpha beta"));
        assertFalse(transactionActive);
        verify(row, never()).getObject("post_id");
    }

    @Test
    void given__ready_without_ranked_row__when__search__then__return_empty_after_transaction() throws Exception {
        when(row.getObject("post_id")).thenReturn(null);

        assertEquals(List.of(), reader.search("alpha beta"));
        assertFalse(transactionActive);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"unexpected\":true}", "[{}]"})
    void given__invalid_image_mapping__when__search__then__do_not_convert_failure_to_unavailable(String stored)
            throws Exception {
        when(row.getString("image_urls")).thenReturn(stored);

        assertThrows(IllegalStateException.class, () -> reader.search("alpha beta"));
        assertFalse(transactionActive);
    }

    @Test
    void given__invalid_board_type__when__search__then__do_not_return_unvalidated_board_or_503() throws Exception {
        when(row.getString("board_type")).thenReturn("UNKNOWN");

        assertThrows(IllegalArgumentException.class, () -> reader.search("alpha beta"));
        assertFalse(transactionActive);
    }

    @Test
    void given__sql_failure__when__search__then__wrap_as_unavailable_after_transaction() {
        var failure = new DataAccessResourceFailureException("private SQL detail");
        doThrow(failure).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<PostSnapshot>>any(),
                eq(KoreanSearchTerms.VERSION), anyString(), anyString());

        assertSame(failure, assertThrows(PostSearchUnavailableException.class,
                () -> reader.search("alpha beta")).getCause());
        assertFalse(transactionActive);
    }

    @ParameterizedTest
    @MethodSource("executionFailures")
    void given__transaction_execution_failure__when__search__then__only_confirmed_unavailability_is_wrapped(
            RuntimeException failure) {
        doThrow(failure).when(transactions).execute(ArgumentMatchers.<TransactionCallback<Object>>any());

        assertSame(failure, assertThrows(PostSearchUnavailableException.class,
                () -> reader.search("alpha beta")).getCause());
        verifyNoInteractions(jdbc);
    }

    private static Stream<Arguments> executionFailures() {
        return Stream.of(Arguments.of(new CannotCreateTransactionException("unavailable")),
                Arguments.of(new TransactionTimedOutException("timeout")));
    }

    @Test
    void given__unexpected_transaction_failure__when__search__then__original_failure_propagates() {
        var failure = new TransactionSystemException("unexpected transaction error");
        doThrow(failure).when(transactions).execute(ArgumentMatchers.<TransactionCallback<Object>>any());

        assertSame(failure, assertThrows(TransactionSystemException.class, () -> reader.search("alpha beta")));
    }

    @Test
    void given__mapped_search__when__nickname_lookup_starts__then__read_transaction_has_finished() {
        SearchPosts search = new SearchPosts(reader, authorId -> {
            assertFalse(transactionActive);
            assertEquals(7L, authorId);
            return Optional.of("작성자");
        });

        assertEquals("작성자", search.search("alpha beta").getFirst().authorNickname());
    }
}
