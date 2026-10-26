package com.pawbridge.backend.contract

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/** The payment order lookup by UUID and the product search document recovery pages, on the real tables. */
class StoreOrderLookupRepositoryContractTest : StoreRepositoryContractBase() {
    private val terms = KoreanSearchTerms()

    @BeforeEach
    fun seed() {
        seedProduct(10, "연어 사료")
        seedSku(100, 10, price = 3_000)
        seedProduct(11, "닭 간식")
        seedSku(110, 11, price = 2_000)
        seedProduct(12, "고양이 장난감")
        seedSku(120, 12, price = 1_000)
        seedProduct(13, "숨김 상품", status = "HIDDEN")
        seedSku(130, 13)
        seedProduct(14, "SKU 없는 상품")
    }

    @AfterEach
    fun close() {
        terms.close()
    }

    private fun documents() = count("SELECT count(*) FROM pawbridge_store.product_search_documents")

    @Test
    fun `주문 UUID가 있을 때__결제용으로 읽으면__주문의 핵심 값을 주고 없는 UUID는 예외다`() {
        // given
        seedOrder(1, 9_933_001, uuid = "11111111-1111-1111-1111-111111111111", status = "PENDING", total = 12_345)
        val orders = JdbcStorePaymentOrders(JdbcTemplate(source), readTransaction())

        // when
        val order = orders.getOrder("11111111-1111-1111-1111-111111111111")

        // then
        assertThat(order.orderId).isEqualTo(1)
        assertThat(order.userId).isEqualTo(9_933_001)
        assertThat(order.totalAmount).isEqualTo(12_345)
        assertThat(order.status).isEqualTo("PENDING")
        assertThatThrownBy { orders.getOrder("none") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `문서가 없는 활성 상품과 낡은 문서가 있을 때__복구하면__대표 SKU 기준으로 다시 쓰고 최신 문서와 숨김 상품은 두며 한도를 지킨다`() {
        // given
        val rebuild = JdbcProductSearchRebuild(writeJdbc(), writeTransaction(), terms)
        rebuild.rebuildPage(100)
        execute("UPDATE pawbridge_store.products SET name = '연어 캔' WHERE id = 10")
        execute("INSERT INTO pawbridge_store.product_skus(id, product_id, sku_code, price, stock_quantity) VALUES (111, 11, 'CHEAPER', 100, 1)")
        val untouched = scalar("SELECT to_jsonb(d)::text FROM pawbridge_store.product_search_documents d WHERE product_id = 12")

        // when
        val second = rebuild.rebuildPage(100)
        val third = rebuild.rebuildPage(100)

        // then
        assertThat(second).`as`("Renamed product 10 and product 11 whose representative SKU changed").isEqualTo(2)
        assertThat(third).isZero()
        assertThat(count("SELECT sku_id FROM pawbridge_store.product_search_documents WHERE product_id = 11")).isEqualTo(111)
        assertThat(scalar("SELECT source_name FROM pawbridge_store.product_search_documents WHERE product_id = 10")).isEqualTo("연어 캔")
        assertThat(scalar("SELECT to_jsonb(d)::text FROM pawbridge_store.product_search_documents d WHERE product_id = 12")).isEqualTo(untouched)
        assertThat(documents()).`as`("Hidden and SKU-less products get no document").isEqualTo(3)
    }

    @Test
    fun `복구할 상품이 한도보다 많을 때__복구하면__상품 번호 순서로 한도만큼만 쓴다`() {
        // given
        val rebuild = JdbcProductSearchRebuild(writeJdbc(), writeTransaction(), terms)

        // when
        val first = rebuild.rebuildPage(2)

        // then
        assertThat(first).isEqualTo(2)
        assertThat(scalar("SELECT string_agg(product_id::text, ',' ORDER BY product_id) FROM pawbridge_store.product_search_documents")).isEqualTo("10,11")
        assertThat(rebuild.rebuildPage(2)).isOne()
    }

    @Test
    fun `다른 세션이 상품 행을 잠그고 있을 때__복구하면__잠긴 상품을 건너뛰고 풀린 뒤에 복구한다`() {
        // given
        val rebuild = JdbcProductSearchRebuild(writeJdbc(), writeTransaction(), terms)

        // when
        val whileLocked = ContractPostgres.adminConnection { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("SELECT 1 FROM pawbridge_store.products WHERE id = 10 FOR UPDATE") }
            val count = rebuild.rebuildPage(100)
            other.rollback()
            count
        }
        val afterRelease = rebuild.rebuildPage(100)

        // then
        assertThat(whileLocked).isEqualTo(2)
        assertThat(afterRelease).isOne()
    }

    @Test
    fun `쪽 크기가 범위 밖이거나 트랜잭션이 없을 때__복구하면__질의 전에 거부한다`() {
        // given
        val rebuild = JdbcProductSearchRebuild(writeJdbc(), writeTransaction(), terms)
        val withoutTransaction = JdbcProductSearchRebuild(writeJdbc(), TransactionOperations.withoutTransaction(), terms)

        // when
        // then
        assertNoSideEffects {
            assertThatThrownBy { rebuild.rebuildPage(0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { rebuild.rebuildPage(101) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { withoutTransaction.rebuildPage(10) }.isInstanceOf(IllegalStateException::class.java)
        }
    }
}
