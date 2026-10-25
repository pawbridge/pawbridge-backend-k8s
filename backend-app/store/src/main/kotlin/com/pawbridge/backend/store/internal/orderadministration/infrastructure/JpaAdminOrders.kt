package com.pawbridge.backend.store.internal.orderadministration.infrastructure

import com.pawbridge.backend.store.api.StoreOrderAdministrators
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.OrderResponse
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Locale

/** Root supplies one writer EM/JpaTM; each PATCH changes only its own Order column, as the original did, with no stock, payment or outbox side effect. */
class JpaAdminOrders internal constructor(
    private val em: EntityManager,
    private val orders: OrderRepository,
    private val writes: TransactionOperations,
    private val reads: TransactionOperations,
    private val administrators: StoreOrderAdministrators,
) : AdminOrders {

    constructor(
        sharedWriter: EntityManager,
        writes: TransactionOperations,
        reads: TransactionOperations,
        administrators: StoreOrderAdministrators,
    ) : this(
        sharedWriter,
        JpaRepositoryFactory(sharedWriter).getRepository(OrderRepository::class.java),
        writes,
        reads,
        administrators,
    )

    override fun list(verifiedAdministratorId: Long, query: AdminOrders.Query): AdminOrders.Page {
        val sortBy = query.sortBy
        if (query.page < 0 || query.size < 1 || query.size > 100 || sortBy == null || !SORTS.contains(sortBy)) {
            throw IllegalArgumentException("Admin order query is not supported")
        }
        val status = if (query.status == null) null else OrderStatus.valueOf(query.status)
        val delivery = if (query.deliveryStatus == null) null else DeliveryStatus.valueOf(query.deliveryStatus)
        val ascending = "asc".equals(query.sortOrder, ignoreCase = true)
        val pageable = PageRequest.of(
            query.page,
            query.size,
            Sort.by(if (ascending) Sort.Direction.ASC else Sort.Direction.DESC, sortBy),
        )
        val userId = query.userId
        val keyword = query.keyword
        return reads.execute {
            transaction(true)
            actor(verifiedAdministratorId, administrators.currentForRead(verifiedAdministratorId))
            var spec: Specification<Order> = Specification.where(null)
            if (status != null) {
                spec = spec.and { root, _, cb -> cb.equal(root.get<Any>("status"), status) }
            }
            if (delivery != null) {
                spec = spec.and { root, _, cb -> cb.equal(root.get<Any>("deliveryStatus"), delivery) }
            }
            if (userId != null) {
                spec = spec.and { root, _, cb -> cb.equal(root.get<Any>("userId"), userId) }
            }
            if (keyword != null && !isJavaBlank(keyword)) {
                // Java's toLowerCase() uses the default locale; Kotlin's lowercase() would use the root locale.
                val pattern = "%" + keyword.lowercase(Locale.getDefault()) + "%"
                spec = spec.and { root, _, cb ->
                    cb.or(
                        cb.like(cb.lower(root.get<String>("orderUuid")), pattern),
                        cb.like(cb.lower(root.get<String>("receiverName")), pattern),
                    )
                }
            }
            val page = orders.findAll(spec, pageable)
            if (page.hasContent()) {
                // Fetch only this page's collections; pagination stays on the root-order query.
                em.createQuery(
                    "select distinct o from Order o left join fetch o.orderItems where o.id in :ids",
                    Order::class.java,
                )
                    .setParameter("ids", page.content.map { order -> order.id })
                    .resultList
            }
            val details = page.map { order -> details(order) }
            AdminOrders.Page(
                details.content,
                details.number,
                details.size,
                details.totalElements,
                sortBy,
                ascending,
            )
        }!!
    }

    override fun get(verifiedAdministratorId: Long, orderId: Long): DirectOrders.OrderDetails =
        reads.execute {
            transaction(true)
            actor(verifiedAdministratorId, administrators.currentForRead(verifiedAdministratorId))
            details(orders.findById(orderId).orElseThrow { AdminOrderNotFoundException() })
        }!!

    override fun updateDelivery(
        verifiedAdministratorId: Long,
        orderId: Long,
        deliveryStatus: String?,
    ): DirectOrders.OrderDetails {
        val delivery = if (deliveryStatus == null) null else DeliveryStatus.valueOf(deliveryStatus)
        return writes.execute {
            transaction(false)
            actor(verifiedAdministratorId, administrators.currentForWrite(verifiedAdministratorId))
            val order = em.find(Order::class.java, orderId, LockModeType.PESSIMISTIC_WRITE)
            if (order == null) {
                throw IllegalArgumentException("Order not found")
            }
            if (delivery == null) {
                throw IllegalStateException("Delivery status cannot be stored")
            }
            order.updateDeliveryStatus(delivery)
            em.flush()
            details(order)
        }!!
    }

    override fun updateStatus(
        verifiedAdministratorId: Long,
        orderId: Long,
        status: String?,
    ): DirectOrders.OrderDetails {
        val value = if (status == null) null else OrderStatus.valueOf(status)
        return writes.execute {
            transaction(false)
            actor(verifiedAdministratorId, administrators.currentForWrite(verifiedAdministratorId))
            val order = em.find(Order::class.java, orderId, LockModeType.PESSIMISTIC_WRITE)
            if (order == null) {
                throw IllegalArgumentException("Order not found")
            }
            if (value == null) {
                throw IllegalStateException("Order status cannot be stored")
            }
            order.updateStatus(value)
            em.flush()
            details(order)
        }!!
    }

    private companion object {
        private val SORTS = setOf(
            "id", "userId", "orderUuid", "totalAmount", "status", "deliveryAddress", "deliveryStatus",
            "receiverName", "receiverPhone", "deliveryMessage", "createdAt", "updatedAt",
        )

        // Java's String.isBlank() uses Character.isWhitespace; Kotlin's isBlank() also treats NBSP as blank.
        private fun isJavaBlank(text: String): Boolean = text.all { Character.isWhitespace(it) }

        private fun actor(user: Long, actor: StoreOrderAdministrators.Actor?) {
            if (actor == null ||
                actor.userId() != user ||
                !actor.active() ||
                actor.deleted() ||
                "ROLE_ADMIN" != actor.role()
            ) {
                throw AdministratorRejectedException()
            }
        }

        private fun transaction(readOnly: Boolean) {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly() != readOnly
            ) {
                throw IllegalStateException(
                    "An active admin order transaction with the expected read mode is required",
                )
            }
        }

        private fun details(entity: Order): DirectOrders.OrderDetails {
            val source = OrderResponse.from(entity)
            return DirectOrders.OrderDetails(
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
}
