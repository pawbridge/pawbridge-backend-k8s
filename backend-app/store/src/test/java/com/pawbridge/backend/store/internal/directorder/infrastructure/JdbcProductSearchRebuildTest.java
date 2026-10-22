package com.pawbridge.backend.store.internal.directorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcProductSearchRebuildTest {
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final KoreanSearchTerms terms = new KoreanSearchTerms();
    private final List<String> events = new ArrayList<>();
    private final List<Map<String, ?>> upserts = new ArrayList<>();
    private final RuntimeException failure = new IllegalStateException("synthetic recovery failure");
    private String failAt;
    private boolean markActual = true;
    private JdbcProductSearchRebuild rebuild;

    @BeforeEach
    void prepare() {
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
        rebuild = new JdbcProductSearchRebuild(jdbc, transaction, terms);
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class))).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); events.add("query");
            String sql = call.getArgument(0);
            Map<String, ?> values = call.getArgument(1);
            assertEquals(KoreanSearchTerms.VERSION, values.get("version"));
            assertTrue(sql.contains("FROM pawbridge_store.products p JOIN pawbridge_store.product_search_source s ON s.product_id=p.id"));
            assertTrue(sql.contains("LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=p.id"));
            assertTrue(sql.contains("WHERE p.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id"));
            assertTrue(sql.contains("OR d.source_name<>s.name OR d.source_option<>s.option_name OR d.analyzer_version<>:version)"));
            assertTrue(sql.contains("ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED"));
            if ("query".equals(failAt)) throw failure;
            return (Integer) values.get("limit") == 1 ? List.of(2L) : List.of(2L, 8L);
        });
        when(jdbc.queryForMap(anyString(), anyMap())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); events.add("source");
            assertEquals("SELECT sku_id,name,option_name FROM pawbridge_store.product_search_source WHERE product_id=:id",
                    call.getArgument(0));
            long id = (Long) call.<Map<String, ?>>getArgument(1).get("id");
            return Map.of("sku_id", id * 10, "name", "강아지 사료", "option_name", "용량: 2kg");
        });
        when(jdbc.update(anyString(), anyMap())).thenAnswer(call -> {
            events.add("upsert");
            assertTrue(call.<String>getArgument(0).contains("INSERT INTO pawbridge_store.product_search_documents"));
            upserts.add(call.getArgument(1));
            if ("upsert".equals(failAt)) throw failure;
            return 1;
        });
    }

    @AfterEach
    void closeAnalyzer() { terms.close(); }

    @ParameterizedTest @CsvSource({"1,1", "100,2"})
    void given__bounded_missing_or_stale_documents__when__recovering__then__read_each_source_and_reuse_the_existing_upsert_in_one_transaction(int limit, int count) {
        assertEquals(count, rebuild.rebuildPage(limit));
        assertEquals(count, upserts.size());
        for (int index = 0; index < count; index++) {
            var values = upserts.get(index);
            long id = index == 0 ? 2L : 8L;
            assertEquals(id, values.get("id")); assertEquals(id * 10, values.get("skuId"));
            assertEquals("강아지 사료", values.get("name")); assertEquals("용량: 2kg", values.get("option"));
            assertEquals(KoreanSearchTerms.VERSION, values.get("version"));
            assertFalse(((String) values.get("nameTerms")).isBlank()); assertFalse(((String) values.get("optionTerms")).isBlank());
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
    void given__no_recovery_candidates__when__recovering__then__return_zero_and_issue_no_source_read_or_upsert() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class))).thenReturn(List.of());
        assertEquals(0, rebuild.rebuildPage(100));
        verify(jdbc, never()).queryForMap(anyString(), anyMap());
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @ParameterizedTest @ValueSource(strings = {"query", "upsert", "commit"})
    void given__query_projection_or_commit_failure__when__recovering__then__propagate_the_same_failure_and_stop_following_work(String stage) {
        failAt = stage;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> rebuild.rebuildPage(100)));
        assertEquals(stage.equals("query") ? 0 : stage.equals("upsert") ? 1 : 2, upserts.size());
        assertEquals("failure", events.getLast());
        if (!stage.equals("commit")) assertFalse(events.contains("commit"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
