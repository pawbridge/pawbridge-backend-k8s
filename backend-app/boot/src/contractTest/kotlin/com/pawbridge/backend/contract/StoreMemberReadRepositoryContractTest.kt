package com.pawbridge.backend.contract

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreMemberReads.Order
import com.pawbridge.backend.store.api.StoreMemberReads.Request
import com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The three my-page reads (wishlists, orders, cart) on the real tables. */
class StoreMemberReadRepositoryContractTest : StoreRepositoryContractBase() {
    private lateinit var reads: StoreMemberReads

    @BeforeEach
    fun build() {
        reads = JdbcStoreMemberReads(readJdbc(), readTransaction())
        seedCategory(1, "사료")
        seedProduct(10, "Apple 사료", category = 1)
        seedSku(100, 10, price = 3_000)
        seedOption(100, 1, "맛", 1, "연어")
        seedOption(100, 2, "크기", 2, "대")
        seedProduct(11, "banana 간식")
        seedSku(110, 11, price = 1_000)
        seedSku(111, 11, price = 9_000)
    }

    private fun page(number: Int = 0, size: Int = 10, vararg order: Order) = Request(number, size, order.toList())

    @Test
    fun `내 찜과 남의 찜이 있을 때__찜을 읽으면__내 것만 정렬대로 쪼개고 한 쪽의 옵션을 모두 펼친다`() {
        // given
        seedWishlist(1, ME, 100, createdAt = "2026-03-01 00:00:01")
        seedWishlist(2, ME, 110, createdAt = "2026-03-01 00:00:02")
        seedWishlist(3, ME, 111, createdAt = "2026-03-01 00:00:03")
        seedWishlist(4, OTHER, 100)

        // when
        val newest = reads.wishlists(ME, page(0, 2, Order("createdAt", false, false)))
        val second = reads.wishlists(ME, page(1, 2, Order("createdAt", false, false)))
        val byPrice = reads.wishlists(ME, page(0, 10, Order("sku.price", true, false)))
        val byName = reads.wishlists(ME, page(0, 10, Order("sku.product.name", true, true)))

        // then
        assertThat(newest.content.map { it.wishlistId }).isEqualTo(listOf(3L, 2L))
        assertThat(newest.totalElements).isEqualTo(3)
        assertThat(second.content.map { it.wishlistId }).isEqualTo(listOf(1L))
        assertThat(second.content.single().options).isEqualTo(mapOf<String?, String?>("맛" to "연어", "크기" to "대"))
        assertThat(second.content.single().categoryName).isEqualTo("사료")
        assertThat(byPrice.content.map { it.price }).isEqualTo(listOf(1_000L, 3_000L, 9_000L))
        assertThat(byName.content.map { it.productName }.distinct()).`as`("Case-insensitive name order").isEqualTo(listOf("Apple 사료", "banana 간식"))
        assertThatThrownBy { reads.wishlists(ME, page(0, 10, Order("password", true, false))) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `내 주문과 남의 주문이 있을 때__주문을 읽으면__내 주문만 정렬대로 주고 주문마다 구매 당시 항목을 붙인다`() {
        // given
        seedOrder(1, ME, createdAt = "2026-03-01 00:00:01")
        seedOrder(2, ME, createdAt = "2026-03-01 00:00:02", status = "PENDING")
        seedOrder(3, OTHER)
        execute("INSERT INTO pawbridge_store.order_items(order_id, product_sku_id, product_name, sku_code, price, quantity) VALUES (1, 100, '구매 당시 이름', 'OLD-CODE', 2500, 2), (1, 110, '다른 항목', 'C2', 1000, 1), (3, 100, '남의 항목', 'C3', 1, 1)")

        // when
        val orders = reads.orders(ME, page(0, 10, Order("createdAt", false, false)))
        val partial = reads.orders(ME, page(1, 1, Order("createdAt", false, false)))

        // then
        assertThat(orders.content.map { it.orderId }).isEqualTo(listOf(2L, 1L))
        assertThat(orders.totalElements).isEqualTo(2)
        assertThat(orders.content.last().items!!.map { Triple(it.productName, it.skuCode, it.price) }.toSet())
            .isEqualTo(setOf(Triple("구매 당시 이름", "OLD-CODE", 2500L), Triple("다른 항목", "C2", 1000L)))
        assertThat(orders.content.first().items).isEmpty()
        assertThat(partial.content.map { it.orderId }).isEqualTo(listOf(1L))
        assertThat(partial.totalElements).isEqualTo(2)
        assertThat(reads.orders(999, page()).content).isEmpty()
    }

    @Test
    fun `장바구니가 없거나 있고 일부 SKU가 사라졌을 때__장바구니를 읽으면__없으면 비어 있고 사라진 SKU는 0원 이름 없음으로 준다`() {
        // given
        assertThat(reads.cart(ME)).isEmpty
        execute("INSERT INTO pawbridge_store.carts(id, user_id, created_at, updated_at) VALUES (1, ?, '2026-03-01 00:00:00', '2026-03-01 00:00:00')", ME)
        execute("INSERT INTO pawbridge_store.cart_items(cart_id, product_sku_id, sku_id, quantity) VALUES (1, 100, 100, 2), (1, 9999, 9999, 1)")

        // when
        val cart = reads.cart(ME).get()

        // then
        assertThat(cart.userId).isEqualTo(ME)
        val byId = cart.items!!.associateBy { it.productSkuId }
        assertThat(byId.getValue(100).let { Triple(it.productName, it.price, it.totalPrice) }).isEqualTo(Triple("Apple 사료", 3_000L, 6_000L))
        assertThat(byId.getValue(9999).let { listOf(it.productName, it.skuCode, it.price, it.totalPrice) }).isEqualTo(listOf(null, null, 0L, 0L))
        assertThat(reads.cart(OTHER)).isEmpty
        execute("INSERT INTO pawbridge_store.carts(id, user_id, created_at, updated_at) VALUES (2, ?, '2026-03-01 00:00:00', '2026-03-01 00:00:00')", OTHER)
        assertThat(reads.cart(OTHER).get().items).`as`("An empty cart is still a cart").isEmpty()
    }

    private companion object {
        const val ME = 9_931_001L
        const val OTHER = 9_931_002L
    }
}
