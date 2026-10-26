package com.pawbridge.backend.contract

import com.pawbridge.backend.store.api.StoreMemberReads.Order
import com.pawbridge.backend.store.api.StoreMemberReads.Request
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The direct purchase adapter (JPA): create, read one, read the history, on the real store tables. */
class StoreOrderJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var orders: DirectOrders

    @BeforeEach
    fun seed() {
        seedMember(ME)
        seedMember(OTHER)
        seedSellable(PRODUCT, SKU, stock = 3, price = 4_000)
    }

    private fun purchase(sku: Long = SKU, quantity: Int = 1, address: String = "합성 주소 1") =
        DirectOrders.Command(sku, quantity, "수령인", "010-0000-0000", address, "문 앞")

    private fun page(number: Int = 0, size: Int = 10) = Request(number, size, listOf(Order("createdAt", false, false)))

    @Test
    fun `판매 중인 SKU가 있을 때__구매하면__대기 주문과 구매 당시 항목을 저장하고 재고를 줄이고 검색 문서를 만든다`() {
        // given
        // when
        val order = orders.create(ME, purchase(quantity = 2))

        // then
        assertThat(order.status).isEqualTo("PENDING")
        assertThat(order.deliveryStatus).isEqualTo("READY")
        assertThat(order.totalAmount).isEqualTo(8_000L)
        assertThat(order.items!!.single().let { Triple(it.skuCode, it.price, it.quantity) }).isEqualTo(Triple("SKU-$SKU", 4_000L, 2))
        assertThat(orderField(order.orderId!!, "user_id")).isEqualTo(ME.toString())
        assertThat(orderField(order.orderId!!, "delivery_address")).isEqualTo("합성 주소 1")
        assertThat(count("SELECT count(*) FROM pawbridge_store.order_items WHERE order_id = ?", order.orderId!!)).isEqualTo(1)
        assertThat(stockOf(SKU)).isEqualTo(1)
        assertThat(searchDocumentCount(PRODUCT)).isEqualTo(1)
        assertThat(orders.get(ME, order.orderId!!)).isEqualTo(order)
    }

    @Test
    fun `재고가 모자라거나 상품이 숨겨졌거나 SKU가 없을 때__구매하면__거부하고 재고와 주문이 그대로다`() {
        // given
        seedSellable(HIDDEN_PRODUCT, HIDDEN_SKU, stock = 5, status = "HIDDEN")

        // when
        // then
        assertThatThrownBy { orders.create(ME, purchase(quantity = 4)) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { orders.create(ME, purchase(HIDDEN_SKU)) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { orders.create(ME, purchase(99_999_999L)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { orders.create(ME, purchase(quantity = 0)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
        assertThat(stockOf(SKU)).isEqualTo(3)
        assertThat(stockOf(HIDDEN_SKU)).isEqualTo(5)
    }

    @Test
    fun `금액이 Long 범위를 넘을 때__구매하면__거부하고 이미 줄인 재고도 되돌린다`() {
        // given
        execute("UPDATE pawbridge_store.product_skus SET price = ? WHERE id = ?", Long.MAX_VALUE, SKU)

        // when
        // then
        assertThatThrownBy { orders.create(ME, purchase(quantity = 2)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(stockOf(SKU)).isEqualTo(3)
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
    }

    @Test
    fun `배송 주소에 4바이트 문자가 있을 때__구매하면__문자셋 제약으로 실패하고 재고를 되돌린다`() {
        // given
        // when
        val failure = catchThrowable { orders.create(ME, purchase(address = "주소 😀")) }

        // then
        assertThat(failure).isNotNull()
        assertThat(PostgresqlRollbackCharsetViolation.matches(failure!!)).isTrue()
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
        assertThat(stockOf(SKU)).isEqualTo(3)
    }

    @Test
    fun `없는 회원과 탈퇴가 시작된 회원일 때__구매하거나 읽으면__행위자 거부이고 아무것도 쓰지 않는다`() {
        // given
        seedDeletingMember(LEAVING)

        // when
        // then
        for (user in listOf(UNKNOWN, LEAVING)) {
            assertThatThrownBy { orders.create(user, purchase()) }.isInstanceOf(OrderActorRejectedException::class.java)
            assertThatThrownBy { orders.get(user, 1) }.isInstanceOf(OrderActorRejectedException::class.java)
            assertThatThrownBy { orders.list(user, null, page()) }.isInstanceOf(OrderActorRejectedException::class.java)
        }
        assertThat(stockOf(SKU)).isEqualTo(3)
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isZero()
    }

    @Test
    fun `남의 주문이거나 없는 주문일 때__읽으면__입력 오류이다`() {
        // given
        val others = orders.create(OTHER, purchase())

        // when
        // then
        assertThatThrownBy { orders.get(ME, others.orderId!!) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { orders.get(ME, 99_999_999L) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `상태가 다른 내 주문들과 남의 주문이 있을 때__이력을 읽으면__내 주문만 최신순으로 쪼개고 항목을 붙인다`() {
        // given
        seedOrder(7_200_001, ME, "uuid-1", status = "PAID", createdAt = "2026-03-01 00:00:01")
        seedOrder(7_200_002, ME, "uuid-2", status = "PENDING", createdAt = "2026-03-01 00:00:02")
        seedOrder(7_200_003, ME, "uuid-3", status = "PAID", createdAt = "2026-03-01 00:00:03")
        seedOrder(7_200_004, OTHER, "uuid-4", status = "PAID")
        for (id in listOf(7_200_001L, 7_200_002L, 7_200_003L, 7_200_004L)) {
            execute(
                "INSERT INTO pawbridge_store.order_items(order_id, product_sku_id, product_name, sku_code, price, quantity) " +
                    "VALUES (?, ?, '구매 당시 이름', 'OLD-CODE', 900, 2)",
                id, SKU,
            )
        }

        // when
        val first = orders.list(ME, null, page(0, 2))
        val second = orders.list(ME, null, page(1, 2))
        val paid = orders.list(ME, "PAID", page())

        // then
        assertThat(first.content.map { it.orderId }).isEqualTo(listOf(7_200_003L, 7_200_002L))
        assertThat(first.totalElements).isEqualTo(3)
        assertThat(second.content.map { it.orderId }).isEqualTo(listOf(7_200_001L))
        assertThat(first.content.first().items!!.single().let { Triple(it.productName, it.skuCode, it.price) })
            .isEqualTo(Triple("구매 당시 이름", "OLD-CODE", 900L))
        assertThat(paid.content.map { it.orderId }).isEqualTo(listOf(7_200_003L, 7_200_001L))
        assertThat(orders.list(OTHER, null, page()).content.map { it.orderId }).isEqualTo(listOf(7_200_004L))
        assertThatThrownBy { orders.list(ME, "NOT_A_STATUS", page()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { orders.list(ME, null, Request(0, 10, listOf(Order("password", true, false)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun catchThrowable(action: () -> Unit): Throwable? = runCatching(action).exceptionOrNull()

    private companion object {
        const val ME = 7_200_101L
        const val OTHER = 7_200_102L
        const val LEAVING = 7_200_103L
        const val UNKNOWN = 7_200_199L
        const val PRODUCT = 7_200_010L
        const val SKU = 7_200_011L
        const val HIDDEN_PRODUCT = 7_200_020L
        const val HIDDEN_SKU = 7_200_021L
    }
}
