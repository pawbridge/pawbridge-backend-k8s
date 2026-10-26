package com.pawbridge.backend.contract

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreMemberReads.Order
import com.pawbridge.backend.store.api.StoreMemberReads.Request
import com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.support.TransactionTemplate

/** The my-page reads of the JPA adapter: wishlists and orders by parent page, then the graph of the selected parents. */
class StoreMemberReadsJpaContractTest : JpaContractBase() {
    @Autowired
    @Qualifier("storeDirectOrderEntityManager")
    lateinit var entityManager: EntityManager

    @Autowired
    @Qualifier("storeDirectOrderReads")
    lateinit var reads: TransactionTemplate

    private lateinit var member: StoreMemberReads

    @BeforeEach
    fun seed() {
        member = JpaStoreMemberReads(entityManager, reads, JdbcStoreMemberReads(readJdbc(), readTransaction()))
        seedCategory(CATEGORY, "사료 $CATEGORY")
        seedProduct(PRODUCT_A, "Apple 사료", category = CATEGORY)
        seedSku(SKU_A, PRODUCT_A, price = 3_000)
        seedOption(SKU_A, GROUP_TASTE, "맛 $GROUP_TASTE", VALUE_SALMON, "연어")
        seedOption(SKU_A, GROUP_SIZE, "크기 $GROUP_SIZE", VALUE_LARGE, "대")
        seedProduct(PRODUCT_B, "banana 간식")
        seedSku(SKU_B, PRODUCT_B, price = 1_000)
        seedSku(SKU_C, PRODUCT_B, price = 9_000)
    }

    private fun page(number: Int = 0, size: Int = 10, vararg order: Order) = Request(number, size, order.toList())

    @Test
    fun `내 찜과 남의 찜이 있을 때__찜을 읽으면__내 것만 정렬대로 쪼개고 한 쪽의 옵션과 분류를 펼친다`() {
        // given
        seedWishlist(7_400_001, ME, SKU_A, createdAt = "2026-03-01 00:00:01")
        seedWishlist(7_400_002, ME, SKU_B, createdAt = "2026-03-01 00:00:02")
        seedWishlist(7_400_003, ME, SKU_C, createdAt = "2026-03-01 00:00:03")
        seedWishlist(7_400_004, OTHER, SKU_A)

        // when
        val newest = member.wishlists(ME, page(0, 2, Order("createdAt", false, false)))
        val second = member.wishlists(ME, page(1, 2, Order("createdAt", false, false)))
        val byPrice = member.wishlists(ME, page(0, 10, Order("sku.price", true, false)))
        val byName = member.wishlists(ME, page(0, 10, Order("sku.product.name", true, true)))

        // then
        assertThat(newest.content.map { it.wishlistId }).isEqualTo(listOf(7_400_003L, 7_400_002L))
        assertThat(newest.totalElements).isEqualTo(3)
        assertThat(second.content.single().options).isEqualTo(mapOf<String?, String?>("맛 $GROUP_TASTE" to "연어", "크기 $GROUP_SIZE" to "대"))
        assertThat(second.content.single().categoryName).isEqualTo("사료 $CATEGORY")
        assertThat(byPrice.content.map { it.price }).isEqualTo(listOf(1_000L, 3_000L, 9_000L))
        assertThat(byName.content.map { it.productName }.distinct()).`as`("Case-insensitive name order").isEqualTo(listOf("Apple 사료", "banana 간식"))
        assertThat(member.wishlists(NOBODY, page()).content).isEmpty()
    }

    @Test
    fun `허용하지 않는 정렬이거나 크기가 한도를 넘을 때__읽으면__DB에 닿기 전에 입력 오류이다`() {
        // given
        // when
        // then
        assertThatThrownBy { member.wishlists(ME, page(0, 10, Order("password", true, false))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { member.orders(ME, page(0, 10, Order("items.sku", true, false))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { member.orders(ME, page(0, 2_001)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `내 주문과 남의 주문이 있을 때__주문을 읽으면__내 주문만 정렬대로 주고 주문마다 구매 당시 항목을 붙인다`() {
        // given
        seedOrder(7_500_001, ME, "order-7500001", createdAt = "2026-03-01 00:00:01")
        seedOrder(7_500_002, ME, "order-7500002", status = "PENDING", createdAt = "2026-03-01 00:00:02")
        seedOrder(7_500_003, OTHER, "order-7500003")
        execute(
            "INSERT INTO pawbridge_store.order_items(order_id, product_sku_id, product_name, sku_code, price, quantity) VALUES " +
                "(7500001, ?, '구매 당시 이름', 'OLD-CODE', 2500, 2), (7500001, ?, '다른 항목', 'C2', 1000, 1), (7500003, ?, '남의 항목', 'C3', 1, 1)",
            SKU_A, SKU_B, SKU_A,
        )

        // when
        val orders = member.orders(ME, page(0, 10, Order("createdAt", false, false)))
        val partial = member.orders(ME, page(1, 1, Order("createdAt", false, false)))

        // then
        assertThat(orders.content.map { it.orderId }).isEqualTo(listOf(7_500_002L, 7_500_001L))
        assertThat(orders.totalElements).isEqualTo(2)
        assertThat(orders.content.last().items!!.map { Triple(it.productName, it.skuCode, it.price) }.toSet())
            .isEqualTo(setOf(Triple("구매 당시 이름", "OLD-CODE", 2500L), Triple("다른 항목", "C2", 1000L)))
        assertThat(orders.content.first().items).isEmpty()
        assertThat(partial.content.map { it.orderId }).isEqualTo(listOf(7_500_001L))
        assertThat(partial.totalElements).isEqualTo(2)
        assertThat(member.orders(NOBODY, page()).content).isEmpty()
    }

    @Test
    fun `장바구니가 없을 때__읽으면__비어 있고 있으면 기존 JDBC 읽기에 맡긴다`() {
        // given
        assertThat(member.cart(ME)).isEmpty
        execute("INSERT INTO pawbridge_store.carts(id, user_id) VALUES (7600001, ?)", ME)
        execute("INSERT INTO pawbridge_store.cart_items(cart_id, product_sku_id, sku_id, quantity) VALUES (7600001, ?, ?, 2)", SKU_A, SKU_A)

        // when
        val cart = member.cart(ME).get()

        // then
        assertThat(cart.items!!.single().let { Triple(it.productName, it.price, it.totalPrice) }).isEqualTo(Triple("Apple 사료", 3_000L, 6_000L))
    }

    private companion object {
        const val ME = 7_400_101L
        const val OTHER = 7_400_102L
        const val NOBODY = 7_400_199L
        const val CATEGORY = 7_400_010L
        const val PRODUCT_A = 7_400_011L
        const val SKU_A = 7_400_012L
        const val PRODUCT_B = 7_400_021L
        const val SKU_B = 7_400_022L
        const val SKU_C = 7_400_023L
        const val GROUP_TASTE = 7_400_031L
        const val GROUP_SIZE = 7_400_032L
        const val VALUE_SALMON = 7_400_041L
        const val VALUE_LARGE = 7_400_042L
    }
}
