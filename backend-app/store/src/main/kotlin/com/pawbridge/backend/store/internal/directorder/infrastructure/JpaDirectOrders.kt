package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

class JpaDirectOrders internal constructor(
    private val writerOrders: OrderRepository,
    private val readerOrders: OrderRepository,
    private val skus: ProductSKURepository,
    private val stocks: JpaStockAdjustments,
    private val actors: StoreOrderActors,
    private val writes: TransactionOperations,
    private val reads: TransactionOperations,
) : DirectOrders {

    constructor(
        sharedWriter: EntityManager,
        sharedReader: EntityManager,
        writes: TransactionOperations,
        reads: TransactionOperations,
        actors: StoreOrderActors,
        writerSearchJdbc: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(
        JpaRepositoryFactory(sharedWriter),
        JpaRepositoryFactory(sharedReader).getRepository(OrderRepository::class.java),
        writes,
        reads,
        actors,
        ProductSnapshots(writerSearchJdbc, terms),
    )

    private constructor(
        writer: JpaRepositoryFactory,
        readerOrders: OrderRepository,
        writes: TransactionOperations,
        reads: TransactionOperations,
        actors: StoreOrderActors,
        snapshots: ProductSnapshots,
    ) : this(
        writer,
        writer.getRepository(ProductSKURepository::class.java),
        readerOrders,
        writes,
        reads,
        actors,
        snapshots,
    )

    // The same ProductSKURepository instance serves this class and the stock adjustments, as in the Java original.
    private constructor(
        writer: JpaRepositoryFactory,
        skus: ProductSKURepository,
        readerOrders: OrderRepository,
        writes: TransactionOperations,
        reads: TransactionOperations,
        actors: StoreOrderActors,
        snapshots: ProductSnapshots,
    ) : this(
        writer.getRepository(OrderRepository::class.java),
        readerOrders,
        skus,
        JpaStockAdjustments(writer.getRepository(ProductRepository::class.java), skus, snapshots),
        actors,
        writes,
        reads,
    )

    override fun create(verifiedUserId: Long, command: DirectOrders.Command): DirectOrders.OrderDetails =
        writes.execute {
            requireTransaction(false)
            requireActor(verifiedUserId, actors.currentForWrite(verifiedUserId))
            val skuId = command.skuId
            val quantity = command.quantity
            if (skuId == null || quantity == null || skuId <= 0 || quantity <= 0) {
                throw IllegalArgumentException("SKU ID and quantity must be positive")
            }
            stocks.decreaseStocks(mapOf(skuId to quantity))
            val sku = skus.findById(skuId)
                .orElseThrow { IllegalArgumentException("SKU not found") }
            val amount: Long
            try {
                amount = Math.multiplyExact(sku.price!!, quantity.toLong())
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Order amount is out of range", overflow)
            }
            val order = Order.builder()
                .userId(verifiedUserId)
                .orderUuid(UUID.randomUUID().toString())
                .totalAmount(amount)
                .receiverName(command.receiverName)
                .receiverPhone(command.receiverPhone)
                .deliveryAddress(command.deliveryAddress)
                .deliveryMessage(command.deliveryMessage)
                .build()
            order.orderItems.add(
                OrderItem.builder()
                    .order(order)
                    .productSKU(sku)
                    .productName(sku.product!!.name)
                    .skuCode(sku.skuCode)
                    .price(sku.price)
                    .quantity(quantity)
                    .build(),
            )
            writerOrders.save(order)
            details(OrderResponse.from(order))
        }!!

    override fun get(verifiedUserId: Long, orderId: Long): DirectOrders.OrderDetails =
        reads.execute {
            requireTransaction(true)
            requireActor(verifiedUserId, actors.currentForRead(verifiedUserId))
            val order = readerOrders.findById(orderId)
                .orElseThrow { IllegalArgumentException("Order not found") }
            if (order.userId!! != verifiedUserId) {
                throw IllegalArgumentException("Order cannot be accessed by this member")
            }
            details(OrderResponse.from(order))
        }!!

    override fun list(
        verifiedUserId: Long,
        status: String?,
        request: StoreMemberReads.Request,
    ): StoreMemberReads.Page<DirectOrders.OrderDetails> {
        val requestedSort = request.sort()
        if (request.size() > 100 ||
            request.offset() > Int.MAX_VALUE ||
            requestedSort.size > 4 ||
            requestedSort.any { order -> order.property() == null || !HISTORY_SORTS.contains(order.property()) }
        ) {
            throw IllegalArgumentException("Order history page or sort is not supported")
        }
        val selected = if (status == null) null else OrderStatus.valueOf(status)
        val sort = Sort.by(
            requestedSort.map { order ->
                Sort.Order(
                    if (order.ascending()) Sort.Direction.ASC else Sort.Direction.DESC,
                    order.property(),
                    order.ignoreCase(),
                    Sort.NullHandling.NATIVE,
                )
            },
        )
        val pageable = PageRequest.of(request.page(), request.size(), sort)
        return reads.execute {
            requireTransaction(true)
            requireActor(verifiedUserId, actors.currentForRead(verifiedUserId))
            val page = if (selected == null) {
                readerOrders.findByUserIdOrderByCreatedAtDesc(verifiedUserId, pageable)
            } else {
                readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(verifiedUserId, selected, pageable)
            }
            if (page.hasContent()) {
                // Initialize only this owner's page collections in the same persistence context.
                readerOrders.findWithItemsByUserIdAndIdIn(verifiedUserId, page.content.map { order -> order.id })
            }
            val content = page.content
                .map { order -> OrderResponse.from(order) }
                .map { response -> details(response) }
            StoreMemberReads.Page(
                content,
                page.number,
                page.size,
                page.totalElements,
                page.sort.stream()
                    .map { order -> StoreMemberReads.Order(order.property, order.isAscending, order.isIgnoreCase) }
                    .toList(),
            )
        }!!
    }

    companion object {
        // Same scalar Order fields already accepted by Store member reads; no relationship paths.
        private val HISTORY_SORTS = setOf(
            "id", "userId", "orderUuid", "totalAmount", "status",
            "deliveryAddress", "deliveryStatus", "receiverName", "receiverPhone", "deliveryMessage",
            "createdAt", "updatedAt",
        )

        private fun requireActor(verified: Long, actor: StoreOrderActors.Actor?) {
            if (actor == null || actor.userId() != verified || !actor.active() || actor.deleted()) {
                throw OrderActorRejectedException()
            }
        }

        private fun requireTransaction(readOnly: Boolean) {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly() != readOnly
            ) {
                throw IllegalStateException(
                    "An active order transaction with the expected read mode is required",
                )
            }
        }

        @JvmStatic
        internal fun details(source: OrderResponse): DirectOrders.OrderDetails =
            DirectOrders.OrderDetails(
                source.orderId,
                source.orderUuid,
                source.userId,
                source.totalAmount,
                source.status,
                source.deliveryStatus,
                source.receiverName,
                source.receiverPhone,
                source.deliveryAddress,
                source.deliveryMessage,
                source.createdAt,
                source.items!!.map { item ->
                    DirectOrders.Item(item.productName, item.skuCode, item.price, item.quantity)
                },
            )
    }
}
