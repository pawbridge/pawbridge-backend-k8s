package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class JpaStockRestorationTest {
    @ParameterizedTest
    @EnumSource(ProductStatus::class)
    fun given__original_product_state__when__restoring_reserved_stock__then__lock_and_refresh_the_search_document_in_writer(
        status: ProductStatus,
    ) {
        DirectOrderFixture().use { f ->
            f.product.updateStatus(status)

            f.write.executeWithoutResult { f.stocks.increaseStocks(mapOf<Long?, Int?>(80L to 2)) }

            assertEquals(7, f.target.stockQuantity)
            assertEquals(8, f.sibling.stockQuantity)
            assertEquals(
                listOf("begin:writer", "reference", "product-lock", "all-sku-lock", "search", "commit:writer"),
                f.events,
            )
            assertEquals(1, f.searchParameters.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["search"])
    fun given__snapshot_failure__when__restoring_stock__then__propagate_and_mark_writer_rollback(failure: String) {
        DirectOrderFixture().use { f ->
            f.failAt = failure

            assertThrows(RuntimeException::class.java) {
                f.write.executeWithoutResult { f.stocks.increaseStocks(mapOf<Long?, Int?>(80L to 2)) }
            }

            assertEquals("rollback:writer", f.events.last())
            assertFalse(f.events.contains("commit:writer"))
        }
    }
}
