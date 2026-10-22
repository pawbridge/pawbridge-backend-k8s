package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JpaStockRestorationTest {
    @ParameterizedTest @EnumSource(ProductStatus.class)
    void given__original_product_state__when__restoring_reserved_stock__then__lock_and_refresh_the_search_document_in_writer(ProductStatus status) throws Exception {
        try (var f = new DirectOrderFixture()) {
            f.product.updateStatus(status);
            f.write.executeWithoutResult(tx -> f.stocks.increaseStocks(Map.of(80L,2)));
            assertEquals(7,f.target.getStockQuantity());
            assertEquals(8,f.sibling.getStockQuantity());
            assertEquals(List.of("begin:writer","reference","product-lock","all-sku-lock","search","commit:writer"),f.events);
            assertEquals(1,f.searchParameters.size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"search"})
    void given__snapshot_failure__when__restoring_stock__then__propagate_and_mark_writer_rollback(String failure) {
        try (var f = new DirectOrderFixture()) {
            f.failAt=failure;
            assertThrows(RuntimeException.class,() -> f.write.executeWithoutResult(tx -> f.stocks.increaseStocks(Map.of(80L,2))));
            assertEquals("rollback:writer",f.events.getLast());
            assertFalse(f.events.contains("commit:writer"));
        }
    }
}
