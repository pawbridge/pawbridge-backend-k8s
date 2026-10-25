package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Existing stock/search business; Cart consumption and Order creation share the same writer TX. */
class JpaCartOrders internal constructor(
    private val carts: CartRepository,
    private val orders: OrderRepository,
    private val skus: ProductSKURepository,
    private val stocks: JpaStockAdjustments,
    private val actors: StoreOrderActors,
    private val writes: TransactionOperations,
) : CartOrders {

    constructor(
        sharedEntityManager: EntityManager,
        writes: TransactionOperations,
        actors: StoreOrderActors,
        writerSearchJdbc: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(
        JpaRepositoryFactory(sharedEntityManager),
        writes,
        actors,
        ProductSnapshots(writerSearchJdbc, terms),
    )

    private constructor(
        factory: JpaRepositoryFactory,
        writes: TransactionOperations,
        actors: StoreOrderActors,
        snapshots: ProductSnapshots,
    ) : this(
        factory,
        factory.getRepository(ProductSKURepository::class.java),
        writes,
        actors,
        snapshots,
    )

    // The same ProductSKURepository instance serves this class and the stock adjustments, as in the Java original.
    private constructor(
        factory: JpaRepositoryFactory,
        skus: ProductSKURepository,
        writes: TransactionOperations,
        actors: StoreOrderActors,
        snapshots: ProductSnapshots,
    ) : this(
        factory.getRepository(CartRepository::class.java),
        factory.getRepository(OrderRepository::class.java),
        skus,
        JpaStockAdjustments(factory.getRepository(ProductRepository::class.java), skus, snapshots),
        actors,
        writes,
    )

    override fun create(verifiedUserId: Long, command: CartOrders.Command): DirectOrders.OrderDetails =
        writes.execute {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ) {
                throw IllegalStateException("An active checkout writer transaction is required")
            }
            val actor = actors.currentForWrite(verifiedUserId)
            if (actor == null || actor.userId() != verifiedUserId || !actor.active() || actor.deleted()) {
                throw OrderActorRejectedException()
            }
            val cart = carts.findByUserId(verifiedUserId)
                .orElseThrow { IllegalArgumentException("Cart is empty") }
            if (cart.items.isEmpty()) {
                throw IllegalArgumentException("Cart is empty")
            }
            val quantities = LinkedHashMap<Long?, Int?>()
            for (item in cart.items) {
                if (quantities.containsKey(item.skuId)) {
                    throw PersistenceException("Stored cart cannot be read")
                }
                quantities[item.skuId] = item.quantity
            }
            // Preserve Cart GET's missing-SKU filtering without loading prices/entities before the stock locks.
            val existing = skus.findProductIdsBySkuIdIn(quantities.keys)
                .map { reference -> reference.skuId }
                .toSet()
            quantities.keys.retainAll(existing)
            if (quantities.isEmpty()) {
                throw IllegalArgumentException("Cart is empty")
            }
            stocks.decreaseStocks(quantities)
            val purchased = skus.findAllById(quantities.keys)
            if (purchased.size != quantities.size) {
                throw PersistenceException("Checkout items cannot be read")
            }
            var total = 0L
            try {
                for (sku in purchased) {
                    total = Math.addExact(
                        total,
                        Math.multiplyExact(sku.price!!, quantities[sku.id]!!.toLong()),
                    )
                }
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Order amount is out of range", overflow)
            }
            val order = Order.builder()
                .userId(verifiedUserId)
                .orderUuid(UUID.randomUUID().toString())
                .totalAmount(total)
                .receiverName(command.receiverName)
                .receiverPhone(command.receiverPhone)
                .deliveryAddress(command.deliveryAddress)
                .deliveryMessage(command.deliveryMessage)
                .build()
            for (sku in purchased) {
                order.orderItems.add(
                    OrderItem.builder()
                        .order(order)
                        .productSKU(sku)
                        .productName(sku.product!!.name)
                        .skuCode(sku.skuCode)
                        .price(sku.price)
                        .quantity(quantities[sku.id])
                        .build(),
                )
            }
            orders.save(order)
            cart.clearItems()
            JpaDirectOrders.details(OrderResponse.from(order))
        }!!
}
