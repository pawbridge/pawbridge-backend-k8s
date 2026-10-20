package com.pawbridge.backend.store.internal.memberread.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StoreMemberReads;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcStoreMemberReadSortAliasTest {
    // Original Wishlist.sku -> ProductSKU.price, resolved by Spring Data's PropertyPath.
    // These literal aliases cover only that existing to-one scalar expression, not arbitrary JPA paths.
    @ParameterizedTest
    @CsvSource({"skuPrice,true,ASC", "skuPrice,false,DESC", "sku_price,true,ASC", "sku_price,false,DESC"})
    void given__original_sku_price_alias__when__reading_wishlists__then__use_existing_numeric_sort_and_preserve_requested_property(
            String property, boolean ascending, String direction) {
        var jdbc = mock(NamedParameterJdbcOperations.class);
        var transaction = mock(TransactionOperations.class);
        var active = new AtomicBoolean();
        when(transaction.execute(any())).thenAnswer(call -> {
            active.set(true);
            try { return call.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()); }
            finally { active.set(false); }
        });
        when(jdbc.query(anyString(), anyMap(), any(ResultSetExtractor.class))).thenAnswer(call -> {
            assertTrue(active.get()); return List.of();
        });
        var requested = List.of(new StoreMemberReads.Order(property, ascending, true));
        var request = new StoreMemberReads.Request(0, 20, requested);
        var result = new JdbcStoreMemberReads(jdbc, transaction).wishlists(7L, request);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), eq(Map.of("member", 7L, "size", 20, "offset", 0L)), any(ResultSetExtractor.class));
        String order = "ORDER BY s.price " + direction;
        assertEquals(2, sql.getValue().split(order, -1).length - 1); // parent selection and expanded result order
        assertTrue(sql.getValue().contains(order + " LIMIT :size OFFSET :offset"));
        assertTrue(sql.getValue().endsWith(order));
        assertFalse(sql.getValue().contains("lower("));
        assertFalse(sql.getValue().contains(property));
        assertEquals(new StoreMemberReads.Page<>(List.of(), 0, 20, 0L, requested), result);
        verify(transaction, times(1)).execute(any());
        verifyNoMoreInteractions(jdbc, transaction);
        assertFalse(active.get());
    }
}
