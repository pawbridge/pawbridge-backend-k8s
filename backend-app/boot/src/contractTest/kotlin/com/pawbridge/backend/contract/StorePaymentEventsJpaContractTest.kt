package com.pawbridge.backend.contract

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentEvents.Completion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.support.TransactionTemplate

/** The store side of a payment (JPA): completing, failing with stock restoration, and the mock payment. */
class StorePaymentEventsJpaContractTest : JpaContractBase() {
    @Autowired
    lateinit var payments: StorePaymentEvents

    @Autowired
    @Qualifier("storeDirectOrderWrites")
    lateinit var writes: TransactionTemplate

    @BeforeEach
    fun seed() {
        seedSellable(PRODUCT_A, SKU_A, stock = 5, price = 1_000)
        seedSellable(PRODUCT_B, SKU_B, stock = 7, price = 2_000)
        seedOrder(PENDING, BUYER, "pending-uuid", status = "PENDING", total = 4_000)
        seedOrder(PAID, BUYER, "paid-uuid", status = "PAID", total = 4_000)
        seedOrder(CANCELLED, BUYER, "cancelled-uuid", status = "CANCELLED", total = 4_000)
        // The pending order holds SKU A twice and SKU B once: failing it must give back 1 + 2 of A and 4 of B.
        execute(
            "INSERT INTO pawbridge_store.order_items(order_id, product_sku_id, product_name, sku_code, price, quantity) VALUES " +
                "(?, ?, '항목', 'A-1', 1000, 1), (?, ?, '항목', 'A-1', 1000, 2), (?, ?, '항목', 'B-1', 2000, 4)",
            PENDING, SKU_A, PENDING, SKU_A, PENDING, SKU_B,
        )
    }

    private fun complete(uuid: String, amount: Long) = writes.execute { payments.completePayment(uuid, amount) }!!

    private fun fail(uuid: String) = writes.execute { payments.failPayment(uuid) }!!

    @Test
    fun `금액이 같은 대기 주문일 때__결제를 완료하면__결제 완료로 바꾸고 적용됨을 알린다`() {
        // given
        // when
        val result = complete("pending-uuid", 4_000)

        // then
        assertThat(result.completion).isEqualTo(Completion.APPLIED)
        assertThat(orderField(PENDING, "status")).isEqualTo("PAID")
    }

    @Test
    fun `이미 결제됐거나 금액이 다르거나 대기가 아니거나 없는 주문일 때__결제를 완료하면__각각 알리고 주문을 바꾸지 않는다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        val alreadyPaid = complete("paid-uuid", 4_000)
        val wrongAmount = complete("pending-uuid", 3_999)
        val cancelled = complete("cancelled-uuid", 4_000)
        val unknown = complete("no-such-uuid", 4_000)

        // then
        assertThat(alreadyPaid.completion).isEqualTo(Completion.ALREADY_PAID)
        assertThat(listOf(wrongAmount, cancelled, unknown).map { it.completion }).containsOnly(Completion.REJECTED)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `호출자가 롤백을 표시할 때__결제를 완료하면__주문은 대기로 남는다`() {
        // given
        // when
        writes.executeWithoutResult { status ->
            payments.completePayment("pending-uuid", 4_000)
            status.setRollbackOnly()
        }

        // then
        assertThat(orderField(PENDING, "status")).isEqualTo("PENDING")
    }

    @Test
    fun `대기 주문이 있을 때__결제 실패를 처리하면__취소하고 SKU별로 합친 재고를 돌려주고 검색 문서를 갱신한다`() {
        // given
        // when
        val cancelled = fail("pending-uuid")

        // then
        assertThat(cancelled).isTrue()
        assertThat(orderField(PENDING, "status")).isEqualTo("CANCELLED")
        assertThat(stockOf(SKU_A)).isEqualTo(5 + 3)
        assertThat(stockOf(SKU_B)).isEqualTo(7 + 4)
        assertThat(searchDocumentCount(PRODUCT_A)).isEqualTo(1)
        assertThat(searchDocumentCount(PRODUCT_B)).isEqualTo(1)
    }

    @Test
    fun `대기가 아니거나 없는 주문일 때__결제 실패를 처리하면__거짓이고 주문도 재고도 그대로다`() {
        // given
        val before = ContractPostgres.snapshot()

        // when
        val results = listOf(fail("paid-uuid"), fail("cancelled-uuid"), fail("no-such-uuid"))

        // then
        assertThat(results).containsOnly(false)
        assertThat(ContractPostgres.snapshot()).isEqualTo(before)
    }

    @Test
    fun `대기 주문과 결제 주문이 있을 때__모의 결제를 처리하면__대기 주문만 결제 완료로 바꾸고 나머지는 거부한다`() {
        // given
        // when
        payments.processPayment(PENDING)

        // then
        assertThat(orderField(PENDING, "status")).isEqualTo("PAID")
        assertThatThrownBy { payments.processPayment(PAID) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { payments.processPayment(CANCELLED) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { payments.processPayment(99_999_999L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(orderField(CANCELLED, "status")).isEqualTo("CANCELLED")
    }

    private companion object {
        const val BUYER = 7_600_101L
        const val PRODUCT_A = 7_600_010L
        const val SKU_A = 7_600_011L
        const val PRODUCT_B = 7_600_020L
        const val SKU_B = 7_600_021L
        const val PENDING = 7_600_201L
        const val PAID = 7_600_202L
        const val CANCELLED = 7_600_203L
    }
}
