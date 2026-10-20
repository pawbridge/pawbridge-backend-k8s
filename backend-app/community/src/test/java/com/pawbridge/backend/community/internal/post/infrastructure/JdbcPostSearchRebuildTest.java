package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcPostSearchRebuildTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final Connection connection = mock(Connection.class);
    private final List<String> events = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();
    private final RuntimeException failure = new IllegalStateException("synthetic recovery failure");
    private String failAt;
    private boolean markActual = true;
    private JdbcPostWriter writer;
    private JdbcPostSearchRebuild rebuild;

    @BeforeEach
    void prepare() throws Exception {
        TransactionOperations transaction = new TransactionOperations() {
            public <T> T execute(TransactionCallback<T> callback) {
                events.add("begin");
                boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
                TransactionSynchronizationManager.setActualTransactionActive(markActual);
                try {
                    T result = callback.doInTransaction(new SimpleTransactionStatus());
                    events.add("commit");
                    if ("commit".equals(failAt)) throw failure;
                    return result;
                } catch (RuntimeException rejected) { events.add("failure"); throw rejected; }
                finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
            }
        };
        writer = new JdbcPostWriter(jdbc, transaction, new ObjectMapper());
        rebuild = new JdbcPostSearchRebuild(jdbc, transaction, writer);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); events.add("query");
            assertEquals(KoreanSearchTerms.VERSION, call.getArgument(1));
            int limit = call.getArgument(2);
            String sql = call.getArgument(0);
            assertTrue(sql.contains("p.deleted_at IS NULL"));
            assertTrue(sql.contains("d.post_id IS NULL OR d.title_hash<>md5(p.title)"));
            assertTrue(sql.contains("d.content_hash<>md5(p.content) OR d.analyzer_version<>?"));
            assertTrue(sql.contains("ORDER BY p.post_id LIMIT ? FOR UPDATE OF p SKIP LOCKED"));
            if ("query".equals(failAt)) throw failure;
            return limit == 1 ? List.of(row(2)) : List.of(row(2), row(8));
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(call.<String>getArgument(0).contains("ON CONFLICT (post_id) DO UPDATE"));
            var values = new ArrayList<Object>(java.util.Collections.nCopies(6, null)); bindings.add(values);
            var statement = mock(PreparedStatement.class);
            doAnswer(binding -> { values.set(binding.<Integer>getArgument(0) - 1, binding.getArgument(1)); return null; })
                    .when(statement).setObject(anyInt(), any());
            return statement;
        });
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(call -> {
            events.add("upsert");
            call.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            if ("upsert".equals(failAt)) throw failure;
            return 1;
        });
    }

    @AfterEach
    void closeAnalyzer() { writer.close(); }

    @ParameterizedTest @CsvSource({"1,1", "100,2"})
    void given__bounded_missing_or_stale_documents__when__recovering__then__use_one_transaction_and_the_existing_weighted_upsert_and_analyzer(int limit, int count) {
        assertEquals(count, rebuild.rebuildPage(limit));
        assertEquals(count, bindings.size());
        for (int index = 0; index < count; index++) {
            var values = bindings.get(index);
            assertEquals(index == 0 ? 2L : 8L, values.get(0));
            assertEquals("유기 동물", values.get(1)); assertEquals("보호소 소식", values.get(2));
            assertEquals(KoreanSearchTerms.VERSION, values.get(3));
            assertFalse(((String) values.get(4)).isBlank()); assertFalse(((String) values.get(5)).isBlank());
        }
        assertEquals("begin", events.getFirst()); assertEquals("query", events.get(1)); assertEquals("commit", events.getLast());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @ParameterizedTest @ValueSource(ints = {0, 101})
    void given__an_out_of_range_page__when__recovering__then__enter_the_transaction_but_execute_no_sql(int limit) {
        assertThrows(IllegalArgumentException.class, () -> rebuild.rebuildPage(limit));
        assertEquals(List.of("begin", "failure"), events); verifyNoInteractions(jdbc);
    }

    @Test
    void given__a_transaction_operation_without_an_actual_transaction__when__recovering_an_invalid_limit__then__reject_the_resource_boundary_first() {
        markActual = false;
        assertThrows(IllegalStateException.class, () -> rebuild.rebuildPage(0));
        verifyNoInteractions(jdbc);
    }

    @Test
    void given__no_recovery_candidates__when__recovering__then__return_zero_and_issue_no_upsert() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertEquals(0, rebuild.rebuildPage(100));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
    }

    @ParameterizedTest @ValueSource(strings = {"query", "upsert", "commit"})
    void given__query_projection_or_commit_failure__when__recovering__then__propagate_the_same_failure_and_stop_following_work(String stage) {
        failAt = stage;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> rebuild.rebuildPage(100)));
        assertEquals(stage.equals("query") ? 0 : stage.equals("upsert") ? 1 : 2, bindings.size());
        assertEquals("failure", events.getLast());
        if (!stage.equals("commit")) assertFalse(events.contains("commit"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static Map<String, Object> row(long id) { return Map.of("post_id", id, "title", "유기 동물", "content", "보호소 소식"); }
}
