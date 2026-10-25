package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.projection.OrderResponse
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.projection.WishlistResponse
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyOrderRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyWishlistRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionOperations
import java.util.Optional

/** Parent ID paging precedes the selected parents' original graphs in Root's read-only, repeatable-read JpaTM transaction. */
class JpaStoreMemberReads internal constructor(
    private val wishlists: MyWishlistRepository,
    private val orders: MyOrderRepository,
    private val reads: TransactionOperations,
    private val carts: StoreMemberReads,
) : StoreMemberReads {

    constructor(
        sharedEntityManager: EntityManager,
        reads: TransactionOperations,
        carts: StoreMemberReads,
    ) : this(JpaRepositoryFactory(sharedEntityManager), reads, carts)

    private constructor(
        factory: JpaRepositoryFactory,
        reads: TransactionOperations,
        carts: StoreMemberReads,
    ) : this(
        factory.getRepository(MyWishlistRepository::class.java),
        factory.getRepository(MyOrderRepository::class.java),
        reads,
        carts,
    )

    override fun wishlists(
        memberId: Long,
        request: StoreMemberReads.Request,
    ): StoreMemberReads.Page<StoreMemberReads.Wishlist> {
        val pageable = pageable(request, WISHLIST_SORTS)
        return execute {
            val ids = wishlists.findIdsByUserId(memberId, pageable)
            val selected = ids.content.stream().map { it.id }.toList()
            if (selected.isEmpty()) {
                page(ids, listOf<StoreMemberReads.Wishlist>())
            } else {
                val content = ordered(selected, wishlists.findByUserIdAndIdIn(memberId, selected)) { entity -> entity.id }
                    .stream()
                    .map { WishlistResponse.from(it) }
                    .map { wishlist(it) }
                    .toList()
                page(ids, content)
            }
        }
    }

    override fun orders(
        memberId: Long,
        request: StoreMemberReads.Request,
    ): StoreMemberReads.Page<StoreMemberReads.Purchase> {
        val pageable = pageable(request, ORDER_SORTS)
        return execute {
            val ids = orders.findIdsByUserId(memberId, pageable)
            val selected = ids.content.stream().map { it.id }.toList()
            if (selected.isEmpty()) {
                page(ids, listOf<StoreMemberReads.Purchase>())
            } else {
                val content = ordered(selected, orders.findByUserIdAndIdIn(memberId, selected)) { entity -> entity.id }
                    .stream()
                    .map { OrderResponse.from(it) }
                    .map { purchase(it) }
                    .toList()
                page(ids, content)
            }
        }
    }

    override fun cart(memberId: Long): Optional<StoreMemberReads.Cart> {
        // Keep the existing persisted-cart JDBC read and its own transaction boundary.
        return carts.cart(memberId)
    }

    private fun <T> execute(action: () -> T): T {
        try {
            return reads.execute { action() }!!
        } catch (failure: DataAccessException) {
            throw StoreMemberReadUnavailableException(failure)
        } catch (failure: TransactionException) {
            throw StoreMemberReadUnavailableException(failure)
        } catch (failure: PersistenceException) {
            throw StoreMemberReadUnavailableException(failure)
        }
    }

    private fun wishlist(source: WishlistResponse): StoreMemberReads.Wishlist =
        StoreMemberReads.Wishlist(
            source.wishlistId,
            source.skuId,
            source.skuCode,
            source.productId,
            source.productName,
            source.productDescription,
            source.productImageUrl,
            source.productStatus,
            source.price,
            source.options?.toMutableMap(),
            source.categoryId,
            source.categoryName,
            source.createdAt,
        )

    private fun purchase(source: OrderResponse): StoreMemberReads.Purchase =
        StoreMemberReads.Purchase(
            source.orderId,
            source.orderUuid,
            source.totalAmount,
            source.status,
            source.deliveryAddress,
            source.deliveryStatus,
            source.items!!.stream().map { item ->
                StoreMemberReads.OrderItem(
                    item.orderItemId,
                    item.productSkuId,
                    item.productName,
                    item.skuCode,
                    item.price,
                    item.quantity,
                )
            }.toList(),
            source.createdAt,
        )

    private fun <T> ordered(selected: List<Long?>, fetched: List<T>, identity: (T) -> Long?): List<T> {
        val byId = HashMap<Long?, T>()
        fetched.forEach { entity -> byId.putIfAbsent(identity(entity), entity) }
        return selected.stream().map { id ->
            val entity = byId[id]
            if (entity == null) {
                throw PersistenceException("Store member page could not be read")
            }
            entity
        }.toList()
    }

    private fun <T> page(source: org.springframework.data.domain.Page<*>, content: List<T>): StoreMemberReads.Page<T> =
        StoreMemberReads.Page(
            content,
            source.number,
            source.size,
            source.totalElements,
            source.sort.stream().map { order ->
                StoreMemberReads.Order(order.property, order.isAscending, order.isIgnoreCase)
            }.toList(),
        )

    private companion object {
        private val WISHLIST_SORTS = java.util.Set.of(
            "id", "userId", "createdAt", "updatedAt",
            "sku.id", "sku.skuCode", "sku.price", "sku.stockQuantity",
            "sku.createdAt", "sku.updatedAt",
            "sku.product.id", "sku.product.name", "sku.product.description", "sku.product.imageUrl",
            "sku.product.status", "sku.product.viewCount", "sku.product.createdAt", "sku.product.updatedAt",
            "sku.product.category.id", "sku.product.category.name",
            "sku.product.category.description", "sku.product.category.createdAt", "sku.product.category.updatedAt",
            "sku.product.category.parent.id", "sku.product.category.parent.name",
            "skuPrice", "sku_price",
        )

        private val ORDER_SORTS = java.util.Set.of(
            "id", "userId", "orderUuid", "totalAmount", "status", "deliveryAddress", "deliveryStatus",
            "receiverName", "receiverPhone", "deliveryMessage", "createdAt", "updatedAt",
        )

        private fun pageable(request: StoreMemberReads.Request, allowed: Set<String>): PageRequest {
            // Bound query joins before opening the reader transaction; aliases are exact literals only.
            if (request.size() > MAX_PAGE_SIZE || request.sort().size > MAX_SORT_TERMS || request.sort().stream()
                    .anyMatch { order -> order.property == null || !allowed.contains(order.property) }
            ) {
                throw IllegalArgumentException("Store member sort is not supported")
            }
            val sort = Sort.by(
                request.sort().stream().map { order ->
                    Sort.Order(
                        if (order.ascending) Sort.Direction.ASC else Sort.Direction.DESC,
                        order.property!!,
                        order.ignoreCase,
                        Sort.NullHandling.NATIVE,
                    )
                }.toList(),
            )
            return PageRequest.of(request.page(), request.size(), sort)
        }

        private const val MAX_PAGE_SIZE = 2000
        private const val MAX_SORT_TERMS = 4
    }
}
