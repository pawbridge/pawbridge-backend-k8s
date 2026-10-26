package com.pawbridge.backend.store.internal.payment.infrastructure

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentEvents.Completion
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.SmartTransactionObject
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Optional

/** Direct payment calls with synthetic transactions; no JPA/database/Redis execution. */
class JpaStorePaymentEventsTest {
    @Test
    fun `호출자 트랜잭션이 없을 때__완료하거나 실패 처리하면__주문을 잠그기 전에 거부한다`() {
        // given
        val f = Fixture()

        // when
        // then
        assertThatThrownBy { f.service.completePayment("order-uuid", 900L) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
        assertThatThrownBy { f.service.failPayment("order-uuid") }
            .isInstanceOf(IllegalTransactionStateException::class.java)

        assertThat(f.events).isEmpty()
        assertThat(f.order.status).isEqualTo(OrderStatus.PENDING)
    }

    @Test
    fun `읽기 전용 호출자 트랜잭션일 때__완료하면__잠그기 전에 거부한다`() {
        // given
        val f = Fixture()
        val reads = TransactionTemplate(f.manager)
        reads.isReadOnly = true

        // when
        // then
        assertThatThrownBy { reads.execute { f.service.completePayment("order-uuid", 900L) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("An active Store writer transaction is required")

        assertThat(f.events).doesNotContain("lock")
        assertThat(f.order.status).isEqualTo(OrderStatus.PENDING)
    }

    @Test
    fun `금액이 같은 잠긴 대기 주문일 때__완료하면__결제 완료로 표시하고 랭킹은 호출자에게 미룬다`() {
        // given
        val f = Fixture()

        // when
        val result: StorePaymentEvents.Completed = f.writes.execute {
            val completed = f.service.completePayment("order-uuid", 900L)
            assertThat(f.ranks).isEmpty()
            f.events.add("caller-work")
            completed
        }!!

        // then
        assertThat(result.completion).isEqualTo(Completion.APPLIED)
        assertThat(f.order.status).isEqualTo(OrderStatus.PAID)
        assertThat(f.events).containsExactly("begin", "lock", "caller-work", "commit")
        assertThat(f.ranks).isEmpty()
        // The caller runs it after commit and connection release, outside any transaction.
        result.afterCommit.run()
        assertThat(f.events).containsExactly("begin", "lock", "caller-work", "commit", "rank", "rank", "rank")
        assertThat(f.ranks).containsExactly(
            "store:ranking:daily:20261021|100|2.0",
            "store:ranking:daily:20261021|100|3.0",
            "store:ranking:daily:20261021|200|4.0",
        )
        assertThat(f.restorations).isEmpty()
    }

    @Test
    fun `호출자가 롤백할 때__완료가 적용됐더라도__랭킹을 올리지 않는다`() {
        // given
        val f = Fixture()

        // when
        f.writes.executeWithoutResult { status ->
            f.service.completePayment("order-uuid", 900L)
            status.setRollbackOnly()
        }

        // then
        assertThat(f.events).containsExactly("begin", "lock", "rollback")
        assertThat(f.ranks).isEmpty()
    }

    @Test
    fun `커밋 뒤 랭킹이 실패할 때__완료하면__실패를 삼키고 커밋을 유지한다`() {
        // given
        val f = Fixture()
        val attempts = ArrayList<String>()
        val service = f.service(
            StorePaymentEvents.StockRestorer { },
            StorePaymentEvents.Ranking { key, product, quantity ->
                attempts.add(product)
                throw IllegalStateException("synthetic redis failure")
            },
        )

        // when
        val result: StorePaymentEvents.Completed = f.writes.execute { service.completePayment("order-uuid", 900L) }!!

        // then
        assertThat(result.completion).isEqualTo(Completion.APPLIED)
        assertThat(f.events).containsExactly("begin", "lock", "commit")
        assertThatCode { result.afterCommit.run() }.doesNotThrowAnyException()
        assertThat(attempts).containsExactly("100", "100", "200")
    }

    @Test
    fun `이미 결제된 주문일 때__다시 완료하면__랭킹 없이 이미 결제됨을 알린다`() {
        // given
        val f = Fixture()
        f.order.paid()

        // when
        val completion = f.complete("order-uuid", 900L)

        // then
        assertThat(completion).isEqualTo(Completion.ALREADY_PAID)
        assertThat(f.ranks).isEmpty()
        assertThat(f.events).containsExactly("begin", "lock", "commit")
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus::class, names = ["CANCELLED", "FAILED", "COMPLETED"])
    fun `대기 상태가 아닌 주문일 때__완료하면__주문을 바꾸지 않고 거부한다`(before: OrderStatus) {
        // given
        val f = Fixture()
        f.order.updateStatus(before)

        // when
        val completion = f.complete("order-uuid", 900L)

        // then
        assertThat(completion).isEqualTo(Completion.REJECTED)
        assertThat(f.order.status).isEqualTo(before)
        assertThat(f.ranks).isEmpty()
    }

    @Test
    fun `금액이 다르거나 주문이 없을 때__완료하면__주문을 바꾸지 않고 거부한다`() {
        // given
        val f = Fixture()

        // when
        val differentAmount = f.complete("order-uuid", 901L)
        val missingOrder = f.complete("missing", 900L)

        // then
        assertThat(differentAmount).isEqualTo(Completion.REJECTED)
        assertThat(missingOrder).isEqualTo(Completion.REJECTED)
        assertThat(f.order.status).isEqualTo(OrderStatus.PENDING)
        assertThat(f.ranks).isEmpty()
    }

    @Test
    fun `잠긴 대기 주문일 때__실패 처리하면__취소하고 집계한 SKU 재고를 호출자 트랜잭션에서 복원한다`() {
        // given
        val f = Fixture()

        // when
        val cancelled = f.fail()

        // then
        assertThat(cancelled).isTrue()
        assertThat(f.order.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(f.restorations).containsExactly(LinkedHashMap<Long?, Int>(mapOf(11L to 5, 12L to 4)))
        assertThat(f.events).containsExactly("begin", "lock", "restore", "commit")
        assertThat(f.ranks).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus::class, names = ["PENDING"], mode = EnumSource.Mode.EXCLUDE)
    fun `대기 상태가 아닌 주문일 때__실패 처리하면__취소도 복원도 하지 않는다`(before: OrderStatus) {
        // given
        val f = Fixture()
        f.order.updateStatus(before)

        // when
        val cancelled = f.fail()

        // then
        assertThat(cancelled).isFalse()
        assertThat(f.order.status).isEqualTo(before)
        assertThat(f.restorations).isEmpty()
    }

    @Test
    fun `재고 복원이 실패할 때__실패 처리하면__전파하고 호출자 트랜잭션을 롤백한다`() {
        // given
        val f = Fixture()
        val failure = IllegalStateException("synthetic stock failure")
        val service = f.service(
            StorePaymentEvents.StockRestorer { quantities -> throw failure },
            StorePaymentEvents.Ranking { key, product, quantity -> },
        )

        // when
        // then
        assertThatThrownBy { f.writes.execute { service.failPayment("order-uuid") } }.isSameAs(failure)

        assertThat(f.events.last()).isEqualTo("rollback")
    }

    @Test
    fun `참여자 실패를 삼켰을 때__호출자가 커밋하면__공유 트랜잭션은 그래도 롤백된다`() {
        // given
        val f = Fixture()
        val service = f.service(
            StorePaymentEvents.StockRestorer { quantities -> throw IllegalStateException("synthetic") },
            StorePaymentEvents.Ranking { key, product, quantity -> },
        )

        // when
        // then
        assertThatThrownBy {
            f.writes.executeWithoutResult {
                try {
                    service.failPayment("order-uuid")
                } catch (ignored: IllegalStateException) {
                }
            }
        }.isInstanceOf(UnexpectedRollbackException::class.java)

        assertThat(f.events.last()).isEqualTo("rollback")
    }

    @Test
    fun `대기 주문일 때__모의 결제를 실행하면__랭킹이나 이벤트 없이 자체 트랜잭션에서 결제 완료로 표시한다`() {
        // given
        val f = Fixture()

        every { f.orders.findById(any()) } answers {
            f.events.add("order-id")
            Optional.of(f.order)
        }

        // when
        f.service.processPayment(33L)

        // then
        assertThat(f.order.status).isEqualTo(OrderStatus.PAID)
        assertThat(f.ranks).isEmpty()
        assertThat(f.restorations).isEmpty()
        assertThat(f.events).containsExactly("begin", "order-id", "commit")
        verify(exactly = 1) { f.orders.findById(33L) }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus::class, names = ["PENDING"], mode = EnumSource.Mode.EXCLUDE)
    fun `대기 상태가 아닌 주문일 때__모의 결제를 실행하면__원래의 상태 가드를 유지한다`(state: OrderStatus) {
        // given
        val f = Fixture()
        f.order.updateStatus(state)

        every { f.orders.findById(any()) } answers {
            f.events.add("order-id")
            Optional.of(f.order)
        }

        // when
        // then
        assertThatThrownBy { f.service.processPayment(33L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Order is not in PENDING state")

        assertThat(f.events.last()).isEqualTo("rollback")
    }

    @Test
    fun `주문이 없을 때__모의 결제를 실행하면__변경 없이 실패한다`() {
        // given
        val f = Fixture()

        every { f.orders.findById(any()) } returns Optional.empty()

        // when
        // then
        assertThatThrownBy { f.service.processPayment(99L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Order not found")

        assertThat(f.events.last()).isEqualTo("rollback")
        verify(exactly = 1) { f.orders.findById(99L) }
    }

    private class Fixture {
        val events = ArrayList<String>()
        val ranks = ArrayList<String>()
        val restorations = ArrayList<Map<Long?, Int>>()
        val orders: OrderRepository = mockk<OrderRepository>()
        val manager = RecordingManager(events)
        val writes = TransactionTemplate(manager)
        val mandatory = TransactionTemplate(manager)
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"), ZoneId.of("Asia/Seoul"))
        val order: Order = Order.builder()
            .userId(7L)
            .orderUuid("order-uuid")
            .totalAmount(900L)
            .deliveryAddress("synthetic")
            .receiverName("synthetic")
            .receiverPhone("000")
            .build()
        val service: JpaStorePaymentEvents

        init {
            mandatory.propagationBehavior = TransactionDefinition.PROPAGATION_MANDATORY
            ReflectionTestUtils.setField(order, "id", 33L)
            addItem(11L, 100L, 2)
            addItem(11L, 100L, 3)
            addItem(12L, 200L, 4)
            service = service(
                StorePaymentEvents.StockRestorer { quantities ->
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
                    assertThat(order.status).isEqualTo(OrderStatus.CANCELLED)
                    events.add("restore")
                    restorations.add(LinkedHashMap(quantities))
                },
                StorePaymentEvents.Ranking { key, product, quantity ->
                    assertThat(order.status).isEqualTo(OrderStatus.PAID)
                    events.add("rank")
                    ranks.add("$key|$product|$quantity")
                },
            )
        }

        fun service(stock: StorePaymentEvents.StockRestorer, ranking: StorePaymentEvents.Ranking): JpaStorePaymentEvents {
            return JpaStorePaymentEvents(
                orders,
                JpaStorePaymentEvents.OrderLocks { uuid ->
                    events.add("lock")
                    if ("order-uuid" == uuid) Optional.of(order) else Optional.empty()
                },
                writes,
                mandatory,
                stock,
                ranking,
                clock,
            )
        }

        fun complete(uuid: String, amount: Long): Completion {
            val completed: StorePaymentEvents.Completed = writes.execute { service.completePayment(uuid, amount) }!!
            completed.afterCommit.run()
            return completed.completion
        }

        fun fail(): Boolean {
            val cancelled: Boolean = writes.execute { service.failPayment("order-uuid") }!!
            return cancelled
        }

        fun addItem(skuId: Long, productId: Long, quantity: Int) {
            val product = Product.builder().id(productId).name("synthetic").status(ProductStatus.ACTIVE).build()
            val sku = ProductSKU.builder()
                .product(product)
                .skuCode("synthetic-$skuId")
                .price(100L)
                .stockQuantity(0)
                .build()
            ReflectionTestUtils.setField(sku, "id", skuId)
            order.orderItems.add(
                OrderItem.builder()
                    .order(order)
                    .productSKU(sku)
                    .price(100L)
                    .productName("snapshot")
                    .skuCode("snapshot")
                    .quantity(quantity)
                    .build(),
            )
        }
    }

    /** Supports joining (MANDATORY/REQUIRED) so participant failures mark the shared transaction rollback-only. */
    private class RecordingManager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var rollbackOnly = false

        override fun doGetTransaction(): Any {
            return object : SmartTransactionObject {
                override fun isRollbackOnly(): Boolean {
                    return rollbackOnly
                }

                override fun flush() {
                }
            }
        }

        override fun isExistingTransaction(transaction: Any): Boolean {
            return TransactionSynchronizationManager.isActualTransactionActive()
        }

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            rollbackOnly = false
            events.add("begin")
        }

        override fun doSetRollbackOnly(status: DefaultTransactionStatus) {
            rollbackOnly = true
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }
    }
}
