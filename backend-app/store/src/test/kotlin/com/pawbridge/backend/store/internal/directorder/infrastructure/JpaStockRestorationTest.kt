package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional

class JpaStockRestorationTest {
    @ParameterizedTest
    @EnumSource(ProductStatus::class)
    fun `원래 상품 상태가 주어졌을 때__예약된 재고를 복원하면__작성자에서 잠그고 검색 문서를 갱신한다`(
        status: ProductStatus,
    ) {
        DirectOrderFixture().use { f ->
            // given
            f.product.updateStatus(status)

            every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
                f.writer()
                f.events.add("reference")
                listOf(SkuReference(80L, 90L))
            }
            every { f.products.findByIdWithLock(any()) } answers {
                f.writer()
                f.events.add("product-lock")
                Optional.of(f.product)
            }
            every { f.skus.findAllByProductIdWithLock(any()) } answers {
                f.writer()
                f.events.add("all-sku-lock")
                listOf(f.target, f.sibling)
            }
            every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
                f.writer()
                f.events.add("search")
                if (f.failAt == "search") {
                    throw DirectOrderFixture.failure()
                }
                f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
                1
            }

            // when
            f.write.executeWithoutResult { f.stocks.increaseStocks(mapOf<Long?, Int?>(80L to 2)) }

            // then
            assertEquals(7, f.target.stockQuantity)
            assertEquals(8, f.sibling.stockQuantity)
            assertEquals(
                listOf("begin:writer", "reference", "product-lock", "all-sku-lock", "search", "commit:writer"),
                f.events,
            )
            assertEquals(1, f.searchParameters.size)
            verify(exactly = 1) { f.products.findByIdWithLock(90L) }
            verify(exactly = 1) { f.skus.findAllByProductIdWithLock(90L) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["search"])
    fun `스냅샷이 실패할 때__재고를 복원하면__전파하고 작성자 롤백을 표시한다`(failure: String) {
        DirectOrderFixture().use { f ->
            // given
            f.failAt = failure

            every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
                f.writer()
                f.events.add("reference")
                listOf(SkuReference(80L, 90L))
            }
            every { f.products.findByIdWithLock(any()) } answers {
                f.writer()
                f.events.add("product-lock")
                Optional.of(f.product)
            }
            every { f.skus.findAllByProductIdWithLock(any()) } answers {
                f.writer()
                f.events.add("all-sku-lock")
                listOf(f.target, f.sibling)
            }
            every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
                f.writer()
                f.events.add("search")
                if (f.failAt == "search") {
                    throw DirectOrderFixture.failure()
                }
                f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
                1
            }

            // when
            val thrown = assertThrows(RuntimeException::class.java) {
                f.write.executeWithoutResult { f.stocks.increaseStocks(mapOf<Long?, Int?>(80L to 2)) }
            }

            // then
            assertNotNull(thrown)
            assertEquals("rollback:writer", f.events.last())
            assertFalse(f.events.contains("commit:writer"))
        }
    }
}
