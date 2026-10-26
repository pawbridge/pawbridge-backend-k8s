package com.pawbridge.backend.user.internal.storemypage.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.Cart
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.CartItem
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.Order
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.OrderItem
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.Page
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.Purchase
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.Request
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage.Wishlist
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.support.TransactionOperations
import org.springframework.util.StringUtils
import java.util.Objects
import java.util.Optional

/** User-owned subset mapping; Store owns its own read transaction after the User check has ended. */
class ModuleStoreMyPage(
    private val profiles: UserProfileQuery,
    private val read: TransactionOperations,
    private val store: StoreMemberReads,
) : StoreMyPage {

    override fun wishlists(currentUserId: Long, request: Request): Page<Wishlist> {
        requireMember(currentUserId)
        try {
            return page(Objects.requireNonNull(store.wishlists(currentUserId, request(request)))) { item ->
                Wishlist(
                    item.wishlistId,
                    item.productId,
                    item.productName,
                    item.productDescription,
                    item.productImageUrl,
                    item.productStatus,
                    item.categoryId,
                    item.categoryName,
                    item.createdAt,
                )
            }
        } catch (unavailable: Exception) {
            throw IllegalStateException("Store wishlist lookup failed", unavailable)
        }
    }

    override fun orders(currentUserId: Long, request: Request): Page<Purchase> {
        requireMember(currentUserId)
        try {
            return page(Objects.requireNonNull(store.orders(currentUserId, request(request)))) { item ->
                Purchase(
                    item.orderId,
                    item.orderUuid,
                    item.totalAmount,
                    item.status,
                    item.deliveryAddress,
                    item.deliveryStatus,
                    map(item.items) { value ->
                        OrderItem(
                            value.orderItemId,
                            value.productSkuId,
                            value.productName,
                            value.skuCode,
                            value.price,
                            value.quantity,
                        )
                    },
                    item.createdAt,
                )
            }
        } catch (unavailable: Exception) {
            throw IllegalStateException("Store order lookup failed", unavailable)
        }
    }

    override fun cart(currentUserId: Long): Cart? {
        requireMember(currentUserId)
        try {
            val source: StoreMemberReads.Cart = Objects.requireNonNull(store.cart(currentUserId)).orElse(null)
                ?: return null
            Objects.requireNonNull(source.items)
            return Cart(
                source.cartId,
                source.userId,
                map(source.items) { item ->
                    CartItem(
                        item.cartItemId,
                        item.productSkuId,
                        item.productName,
                        item.skuCode,
                        item.price,
                        item.quantity,
                        item.totalPrice,
                    )
                },
                source.createdAt,
            )
        } catch (unavailable: Exception) {
            throw IllegalStateException("Store cart lookup failed", unavailable)
        }
    }

    private fun requireMember(currentUserId: Long) {
        // Begin/lookup/commit failures precede Store and keep their original User failure category.
        read.execute<Any?> {
            profiles.require(currentUserId)
            null
        }
    }

    private companion object {
        /** Match one HTTP sort parameter after User's existing comma flattening, including lost direction boundaries. */
        private fun request(source: Request): StoreMemberReads.Request {
            var raw = source.sort
            if (raw == null || raw.isEmpty()) {
                raw = "createdAt,desc"
            }
            // Java's split drops trailing empty strings and Kotlin's keeps them; blank elements are filtered out
            // right below, so both give the same list.
            val elements = raw.split(",").filter { value -> StringUtils.hasText(value.replace(".", "")) }
            var end = elements.size
            val ignoreCase = end > 0 && elements[end - 1].equals("ignorecase", ignoreCase = true)
            if (ignoreCase) {
                end--
            }
            val direction: Optional<Sort.Direction> =
                if (end == 0) Optional.empty() else Sort.Direction.fromOptionalString(elements[end - 1])
            if (direction.isPresent) {
                end--
            }
            val orders = ArrayList<StoreMemberReads.Order>()
            for (index in 0 until end) {
                val property = elements[index]
                if (StringUtils.hasText(property)) {
                    orders.add(
                        StoreMemberReads.Order(property, direction.orElse(Sort.Direction.ASC).isAscending, ignoreCase),
                    )
                }
            }
            // An empty parsed Sort lets Store's @PageableDefault supply createdAt DESC.
            if (orders.isEmpty()) {
                orders.add(StoreMemberReads.Order("createdAt", false, false))
            }
            // These are the existing Store Pageable resolver defaults, not a new API page cap.
            return StoreMemberReads.Request(
                Math.max(0, source.page),
                if (source.size < 1) 20 else Math.min(2000, source.size),
                orders,
            )
        }

        private fun <T, R> page(source: StoreMemberReads.Page<T>, mapper: (T) -> R): Page<R> {
            val content = map(source.content, mapper)
            // Fixed Store Feign SortJacksonModule serializes an array, so the existing User array branch retains each order.
            val normalizedSort = Sort.by(
                source.sort.stream().map { order ->
                    Sort.Order(
                        if (order.ascending) Sort.Direction.ASC else Sort.Direction.DESC,
                        order.property!!,
                        order.ignoreCase,
                        Sort.NullHandling.NATIVE,
                    )
                }.toList(),
            )
            // PageImpl rejects a null content list, as the Java code did.
            val checkedContent = requireNotNull(content) { "Content must not be null" }
            val normalized: org.springframework.data.domain.Page<R> = if (source.size > 0) {
                PageImpl(checkedContent, PageRequest.of(source.number, source.size, normalizedSort), source.totalElements)
            } else {
                PageImpl(checkedContent)
            }
            return Page(
                normalized.content,
                normalized.number,
                normalized.size,
                normalized.totalElements,
                normalized.sort.stream().map { order ->
                    Order(order.property, order.isAscending, order.isIgnoreCase, order.nullHandling.name)
                }.toList(),
            )
        }

        // A null source element stays a null element (as in Java); the cast only widens the Kotlin element type.
        @Suppress("UNCHECKED_CAST")
        private fun <T, R> map(values: List<T>?, mapper: (T) -> R): List<R>? =
            values?.stream()
                ?.map { value -> if (value == null) null else mapper(value) }
                ?.toList() as List<R>?
    }
}
