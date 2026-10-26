package com.pawbridge.backend.contract

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.support.TransactionTemplate

/**
 * Invariants of the store writers under real concurrency: workers start together on the real database.
 *
 * Writes are slowed inside their transaction (see [withSlowWrites]), so each race is certain: if the row lock of the
 * adapter is removed, the other workers read the old state while the first one writes, and the invariant breaks.
 */
class StoreJpaConcurrencyContractTest : JpaContractBase() {
    @Autowired
    lateinit var orders: DirectOrders

    @Autowired
    lateinit var carts: Carts

    @Autowired
    lateinit var cartOrders: CartOrders

    @Autowired
    lateinit var admin: AdminOrders

    @Autowired
    lateinit var payments: StorePaymentEvents

    @Autowired
    @Qualifier("storeDirectOrderWrites")
    lateinit var writes: TransactionTemplate

    @BeforeEach
    fun seed() {
        seedMember(ADMIN, "ROLE_ADMIN")
        for (buyer in BUYERS) {
            seedMember(buyer)
        }
    }

    private fun purchase(sku: Long, quantity: Int = 1) =
        DirectOrders.Command(sku, quantity, "수령인", "010-0000-0000", "합성 주소 1", null)

    @Test
    fun `재고가 3개인 SKU를 6명이 동시에 구매할 때__모두 주문하면__3명만 성공하고 재고는 0이며 판매 수량과 맞는다`() {
        // given
        seedSellable(PRODUCT, SKU, stock = 3)

        // when
        val results = withSlowWrites("product_skus", "UPDATE") {
            runTogether(BUYERS.size) { index -> orders.create(BUYERS[index], purchase(SKU)) }
        }

        // then
        val succeeded = results.filter { it.isSuccess }
        assertThat(succeeded).`as`("Successful orders never exceed the stock").hasSize(3)
        assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).allSatisfy {
            assertThat(it).isInstanceOf(IllegalStateException::class.java)
        }
        assertThat(stockOf(SKU)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders")).isEqualTo(3)
        assertThat(count("SELECT sum(quantity) FROM pawbridge_store.order_items WHERE product_sku_id = ?", SKU)).isEqualTo(3)
    }

    @Test
    fun `같은 장바구니를 여러 요청이 동시에 주문할 때__주문하면__주문은 하나이고 재고는 한 번만 줄며 나머지는 빈 장바구니 오류이다`() {
        // given
        seedSellable(PRODUCT, SKU, stock = 10)
        carts.add(BUYERS[0], SKU, 2)

        // when
        val results = withSlowWrites("cart_items", "DELETE") {
            runTogether(4) { cartOrders.create(BUYERS[0], CartOrders.Command("수령인", "010-0000-0000", "합성 주소 1", null)) }
        }

        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id = ?", BUYERS[0])).isEqualTo(1)
        assertThat(stockOf(SKU)).isEqualTo(8)
        assertThat(count("SELECT count(*) FROM pawbridge_store.cart_items")).isZero()
        assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull()!!.javaClass }.toSet())
            .allSatisfy { assertThat(RuntimeException::class.java).isAssignableFrom(it) }
    }

    @Test
    fun `같은 회원이 같은 SKU를 동시에 담을 때__처음 담는 요청 셋이 겹치면__장바구니와 항목은 하나이고 수량이 모두 합쳐진다`() {
        // given
        seedSellable(PRODUCT, SKU, stock = 10)

        // when
        val results = withSlowWrites("carts", "INSERT") {
            runTogether(3) { carts.add(BUYERS[0], SKU, 1) }
        }

        // then
        assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
        assertThat(count("SELECT count(*) FROM pawbridge_store.carts WHERE user_id = ?", BUYERS[0])).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM pawbridge_store.cart_items")).isEqualTo(1)
        assertThat(count("SELECT quantity FROM pawbridge_store.cart_items")).isEqualTo(3)
    }

    @Test
    fun `결제 대기 주문이 있을 때__결제 실패를 동시에 여러 번 처리하면__한 번만 취소하고 재고는 한 번만 돌아온다`() {
        // given
        seedSellable(PRODUCT, SKU, stock = 5)
        val order = orders.create(BUYERS[0], purchase(SKU, quantity = 2))
        val uuid = order.orderUuid!!
        assertThat(stockOf(SKU)).isEqualTo(3)

        // when
        val results = withSlowWrites("product_skus", "UPDATE") {
            runTogether(3) { writes.execute { payments.failPayment(uuid) } }
        }

        // then
        assertThat(results.map { it.getOrThrow() }.count { it == true }).isEqualTo(1)
        assertThat(orderField(order.orderId!!, "status")).isEqualTo("CANCELLED")
        assertThat(stockOf(SKU)).`as`("Stock comes back once").isEqualTo(5)
    }

    @Test
    fun `결제된 주문이 있을 때__관리자 둘이 주문 상태와 배송 상태를 동시에 바꾸면__두 변경이 모두 남는다`() {
        // given
        seedMember(SECOND_ADMIN, "ROLE_ADMIN")
        seedOrder(ORDER, BUYERS[0], "race-uuid", status = "PAID")

        // when
        val results = withSlowWrites("orders", "UPDATE") {
            runTogether(2) { index ->
                if (index == 0) {
                    admin.updateStatus(ADMIN, ORDER, "COMPLETED")
                } else {
                    admin.updateDelivery(SECOND_ADMIN, ORDER, "DELIVERED")
                }
            }
        }

        // then
        assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
        assertThat(orderField(ORDER, "status")).isEqualTo("COMPLETED")
        assertThat(orderField(ORDER, "delivery_status")).isEqualTo("DELIVERED")
    }

    private companion object {
        val BUYERS = (0 until 6).map { 7_900_101L + it }
        const val ADMIN = 7_900_001L
        const val SECOND_ADMIN = 7_900_002L
        const val PRODUCT = 7_900_010L
        const val SKU = 7_900_011L
        const val ORDER = 7_900_201L
    }
}
