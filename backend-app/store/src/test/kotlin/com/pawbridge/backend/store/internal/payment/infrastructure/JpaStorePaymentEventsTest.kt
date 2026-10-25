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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
    fun given__no_caller_transaction__when__completing_or_failing__then__reject_before_locking_the_order() {
        val f = Fixture()

        assertThatThrownBy { f.service.completePayment("order-uuid", 900L) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
        assertThatThrownBy { f.service.failPayment("order-uuid") }
            .isInstanceOf(IllegalTransactionStateException::class.java)

        assertThat(f.events).isEmpty()
        assertThat(f.order.status).isEqualTo(OrderStatus.PENDING)
    }

    @Test
    fun given__a_read_only_caller_transaction__when__completing__then__reject_before_locking() {
        val f = Fixture()
        val reads = TransactionTemplate(f.manager)
        reads.isReadOnly = true

        assertThatThrownBy { reads.execute { f.service.completePayment("order-uuid", 900L) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("An active Store writer transaction is required")

        assertThat(f.events).doesNotContain("lock")
        assertThat(f.order.status).isEqualTo(OrderStatus.PENDING)
    }

    @Test
    fun given__a_locked_pending_order_with_the_same_amount__when__completing__then__mark_paid_and_defer_ranking_to_the_caller() {
        val f = Fixture()

        val result: StorePaymentEvents.Completed = f.writes.execute {
            val completed = f.service.completePayment("order-uuid", 900L)
            assertThat(f.ranks).isEmpty()
            f.events.add("caller-work")
            completed
        }!!

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
    fun given__the_caller_rolls_back__when__completion_was_applied__then__never_increment_ranking() {
        val f = Fixture()

        f.writes.executeWithoutResult { status ->
            f.service.completePayment("order-uuid", 900L)
            status.setRollbackOnly()
        }

        assertThat(f.events).containsExactly("begin", "lock", "rollback")
        assertThat(f.ranks).isEmpty()
    }

    @Test
    fun given__ranking_fails_after_commit__when__completing__then__swallow_the_failure_and_keep_the_commit() {
        val f = Fixture()
        val attempts = ArrayList<String>()
        val service = f.service(
            StorePaymentEvents.StockRestorer { },
            StorePaymentEvents.Ranking { key, product, quantity ->
                attempts.add(product)
                throw IllegalStateException("synthetic redis failure")
            },
        )

        val result: StorePaymentEvents.Completed = f.writes.execute { service.completePayment("order-uuid", 900L) }!!

        assertThat(result.completion).isEqualTo(Completion.APPLIED)
        assertThat(f.events).containsExactly("begin", "lock", "commit")
        assertThatCode { result.afterCommit.run() }.doesNotThrowAnyException()
        assertThat(attempts).containsExactly("100", "100", "200")
    }

    @Test
    fun given__an_already_paid_order__when__completing_again__then__report_already_paid_without_ranking() {
        val f = Fixture()
        f.order.paid()

        assertThat(f.complete("order-uuid", 900L)).isEqualTo(Completion.ALREADY_PAID)

        assertThat(f.ranks).isEmpty()
        assertThat(f.events).containsExactly("begin", "lock", "commit")
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus::class, names = ["CANCELLED", "FAILED", "COMPLETED"])
    fun given__a_non_pending_order__when__completing__then__reject_without_changing_it(before: OrderStatus) {
        val f = Fixture()
        f.order.updateStatus(before)

        assertThat(f.complete("order-uuid", 900L)).isEqualTo(Completion.REJECTED)

        assertThat(f.order.status).isEqualTo(before)
        assertThat(f.ranks).isEmpty()
    }

    @Test
    fun given__a_different_amount_or_missing_order__when__completing__then__reject_without_changing_it() {
        val f = Fixture()

        assertThat(f.complete("order-uuid", 901L)).isEqualTo(Completion.REJECTED)
        assertThat(f.complete("missing", 900L)).isEqualTo(Completion.REJECTED)

        assertThat(f.order.status).isEqualTo(OrderStatus.PENDING)
        assertThat(f.ranks).isEmpty()
    }

    @Test
    fun given__a_locked_pending_order__when__failing__then__cancel_and_restore_aggregated_skus_in_the_caller_transaction() {
        val f = Fixture()

        assertThat(f.fail()).isTrue()

        assertThat(f.order.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(f.restorations).containsExactly(LinkedHashMap<Long?, Int>(mapOf(11L to 5, 12L to 4)))
        assertThat(f.events).containsExactly("begin", "lock", "restore", "commit")
        assertThat(f.ranks).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus::class, names = ["PENDING"], mode = EnumSource.Mode.EXCLUDE)
    fun given__a_non_pending_order__when__failing__then__neither_cancel_nor_restore(before: OrderStatus) {
        val f = Fixture()
        f.order.updateStatus(before)

        assertThat(f.fail()).isFalse()

        assertThat(f.order.status).isEqualTo(before)
        assertThat(f.restorations).isEmpty()
    }

    @Test
    fun given__a_stock_restore_failure__when__failing__then__propagate_and_roll_back_the_caller_transaction() {
        val f = Fixture()
        val failure = IllegalStateException("synthetic stock failure")
        val service = f.service(
            StorePaymentEvents.StockRestorer { quantities -> throw failure },
            StorePaymentEvents.Ranking { key, product, quantity -> },
        )

        assertThatThrownBy { f.writes.execute { service.failPayment("order-uuid") } }.isSameAs(failure)

        assertThat(f.events.last()).isEqualTo("rollback")
    }

    @Test
    fun given__a_swallowed_participant_failure__when__the_caller_commits__then__the_shared_transaction_still_rolls_back() {
        val f = Fixture()
        val service = f.service(
            StorePaymentEvents.StockRestorer { quantities -> throw IllegalStateException("synthetic") },
            StorePaymentEvents.Ranking { key, product, quantity -> },
        )

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
    fun given__a_pending_order__when__the_mock_payment_runs__then__mark_paid_in_its_own_transaction_without_ranking_or_events() {
        val f = Fixture()

        f.service.processPayment(33L)

        assertThat(f.order.status).isEqualTo(OrderStatus.PAID)
        assertThat(f.ranks).isEmpty()
        assertThat(f.restorations).isEmpty()
        assertThat(f.events).containsExactly("begin", "order-id", "commit")
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus::class, names = ["PENDING"], mode = EnumSource.Mode.EXCLUDE)
    fun given__a_non_pending_order__when__the_mock_payment_runs__then__preserve_the_original_state_guard(state: OrderStatus) {
        val f = Fixture()
        f.order.updateStatus(state)

        assertThatThrownBy { f.service.processPayment(33L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Order is not in PENDING state")

        assertThat(f.events.last()).isEqualTo("rollback")
    }

    @Test
    fun given__a_missing_order__when__the_mock_payment_runs__then__fail_without_changes() {
        val f = Fixture()
        `when`(f.orders.findById(99L)).thenReturn(Optional.empty())

        assertThatThrownBy { f.service.processPayment(99L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Order not found")

        assertThat(f.events.last()).isEqualTo("rollback")
    }

    private class Fixture {
        val events = ArrayList<String>()
        val ranks = ArrayList<String>()
        val restorations = ArrayList<Map<Long?, Int>>()
        val orders: OrderRepository = mock(OrderRepository::class.java)
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
            `when`(orders.findById(33L)).thenAnswer {
                events.add("order-id")
                Optional.of(order)
            }
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
