package com.pawbridge.backend.store.internal.payment.infrastructure

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.apache.commons.logging.LogFactory
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Optional

/** Store side of the direct payment call. No pool, EMF, message transport or Redis client is owned here. */
class JpaStorePaymentEvents internal constructor(
    private val orders: OrderRepository,
    private val locks: OrderLocks,
    private val writes: TransactionOperations,
    private val mandatoryWrites: TransactionOperations,
    private val stocks: StorePaymentEvents.StockRestorer,
    private val ranking: StorePaymentEvents.Ranking,
    private val clock: Clock,
) : StorePaymentEvents {

    /** Loads one order by UUID with a PostgreSQL row lock (SELECT ... FOR UPDATE) in the caller's transaction. */
    internal fun interface OrderLocks {
        fun lockByUuid(orderUuid: String?): Optional<Order>
    }

    constructor(
        sharedWriter: EntityManager,
        writes: TransactionOperations,
        mandatoryWrites: TransactionOperations,
        stocks: StorePaymentEvents.StockRestorer,
        ranking: StorePaymentEvents.Ranking,
        clock: Clock,
    ) : this(
        JpaRepositoryFactory(sharedWriter).getRepository(OrderRepository::class.java),
        OrderLocks { uuid ->
            val found = sharedWriter.createQuery("select o from Order o where o.orderUuid = :uuid", Order::class.java)
                .setParameter("uuid", uuid)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .resultList
            if (found.size > 1) {
                throw IllegalStateException("Duplicate order UUID")
            }
            found.stream().findFirst()
        },
        writes,
        mandatoryWrites,
        stocks,
        ranking,
        clock,
    )

    override fun completePayment(orderUuid: String?, amount: Long): StorePaymentEvents.Completed =
        mandatoryWrites.execute {
            requireWriter()
            val order = locks.lockByUuid(orderUuid).orElse(null)
            if (order == null) {
                return@execute StorePaymentEvents.Completed(StorePaymentEvents.Completion.REJECTED, NOTHING)
            }
            if (order.status == OrderStatus.PAID) {
                return@execute StorePaymentEvents.Completed(StorePaymentEvents.Completion.ALREADY_PAID, NOTHING)
            }
            if (order.status != OrderStatus.PENDING || order.totalAmount == null || order.totalAmount != amount) {
                return@execute StorePaymentEvents.Completed(StorePaymentEvents.Completion.REJECTED, NOTHING)
            }
            order.paid()
            val key = "store:ranking:daily:" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
            val ranks = order.orderItems.stream()
                .map { item -> Rank(item.productSKU!!.product!!.id.toString(), item.quantity!!.toDouble()) }
                .toList()
            val orderId = order.id
            // Redis is not touched here: the caller runs this after commit and connection release.
            StorePaymentEvents.Completed(StorePaymentEvents.Completion.APPLIED, Runnable { incrementAfterCommit(key, ranks, orderId) })
        }!!

    override fun failPayment(orderUuid: String?): Boolean {
        val cancelled = mandatoryWrites.execute {
            requireWriter()
            val order = locks.lockByUuid(orderUuid).orElse(null)
            if (order == null || order.status != OrderStatus.PENDING) {
                return@execute false
            }
            order.cancelOrder()
            val quantities = LinkedHashMap<Long?, Int>()
            for (item in order.orderItems) {
                quantities.merge(item.productSKU!!.id, item.quantity!!) { first, second -> first + second }
            }
            stocks.increaseStocks(quantities)
            true
        }
        return cancelled == true
    }

    override fun processPayment(orderId: Long) {
        writes.executeWithoutResult {
            val order = orders.findById(orderId).orElseThrow { IllegalArgumentException("Order not found: $orderId") }
            if (order.status != OrderStatus.PENDING) {
                throw IllegalStateException("Order is not in PENDING state. Current: ${order.status}")
            }
            order.paid()
        }
    }

    private fun incrementAfterCommit(key: String, ranks: List<Rank>, orderId: Long?) {
        for (rank in ranks) {
            try {
                ranking.increment(key, rank.productId, rank.quantity)
            } catch (failure: RuntimeException) {
                // The order is already committed; a ranking miss must not turn a paid order into an error response.
                LOG.warn(
                    "Store payment ranking increment failed after commit: orderId=" + orderId +
                        " productId=" + rank.productId + " error=" + failure.javaClass.simpleName,
                )
            }
        }
    }

    private data class Rank(val productId: String, val quantity: Double)

    private companion object {
        private val LOG = LogFactory.getLog(JpaStorePaymentEvents::class.java)

        private val NOTHING = Runnable { }

        private fun requireWriter() {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ) {
                throw IllegalStateException("An active Store writer transaction is required")
            }
        }
    }
}
