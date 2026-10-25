package com.pawbridge.backend.store.api

import java.time.LocalDateTime
import java.util.Optional

/**
 * Store-owned read boundary for a caller that has already authenticated/authorized the member.
 * No member existence check or public HTTP/X-User trust policy is implied by this API.
 * The User module may map these projections into its existing MyPage response without accessing Store internals.
 */
interface StoreMemberReads {
    /** Native database null order, matching the original Spring Data JPA Order. */
    @JvmRecord
    data class Order(
        val property: String?,
        val ascending: Boolean,
        val ignoreCase: Boolean,
    )

    /**
     * Paged request; HTTP defaults (page 0, size 20, createdAt DESC) belong to the caller.
     *
     * A Java record with a compact constructor that validates and copies `sort` (List.copyOf: immutable, rejects
     * nulls). @JvmRecord cannot transform a constructor argument, so this is a plain class that keeps the
     * record-style accessor names, equals, hashCode and toString.
     */
    class Request(
        private val page: Int,
        private val size: Int,
        sort: List<Order>,
    ) {
        private val sort: List<Order>

        init {
            if (page < 0 || size < 1) {
                throw IllegalArgumentException("Page must be non-negative and size positive")
            }
            this.sort = java.util.List.copyOf(sort)
        }

        fun page(): Int = page

        fun size(): Int = size

        fun sort(): List<Order> = sort

        fun offset(): Long = page.toLong() * size

        override fun equals(other: Any?): Boolean =
            other is Request && page == other.page && size == other.size && sort == other.sort

        override fun hashCode(): Int {
            var result = page
            result = 31 * result + size
            result = 31 * result + sort.hashCode()
            return result
        }

        override fun toString(): String = "Request[page=$page, size=$size, sort=$sort]"
    }

    @JvmRecord
    data class Page<T>(
        val content: List<T>,
        val number: Int,
        val size: Int,
        val totalElements: Long,
        val sort: List<Order>,
    )

    @JvmRecord
    data class Wishlist(
        val wishlistId: Long?,
        val skuId: Long?,
        val skuCode: String?,
        val productId: Long?,
        val productName: String?,
        val productDescription: String?,
        val productImageUrl: String?,
        val productStatus: String?,
        val price: Long?,
        val options: MutableMap<String?, String?>?,
        val categoryId: Long?,
        val categoryName: String?,
        val createdAt: LocalDateTime?,
    )

    @JvmRecord
    data class OrderItem(
        val orderItemId: Long?,
        val productSkuId: Long?,
        val productName: String?,
        val skuCode: String?,
        val price: Long?,
        val quantity: Int?,
    )

    @JvmRecord
    data class Purchase(
        val orderId: Long?,
        val orderUuid: String?,
        val totalAmount: Long?,
        val status: String?,
        val deliveryAddress: String?,
        val deliveryStatus: String?,
        val items: MutableList<OrderItem>?,
        val createdAt: LocalDateTime?,
    )

    @JvmRecord
    data class CartItem(
        val cartItemId: Long?,
        val productSkuId: Long?,
        val productName: String?,
        val skuCode: String?,
        val price: Long?,
        val quantity: Int?,
        val totalPrice: Long?,
    )

    @JvmRecord
    data class Cart(
        val cartId: Long?,
        val userId: Long?,
        val items: MutableList<CartItem>?,
        val createdAt: LocalDateTime?,
    )

    /** All wishlist statuses remain visible; includes the original Store SKU/price/options fields. */
    fun wishlists(memberId: Long, request: Request): Page<Wishlist>

    /** Order items contain purchase snapshots rather than current product/SKU values. */
    fun orders(memberId: Long, request: Request): Page<Purchase>

    /** Empty means no persisted cart, corresponding to the original Store HTTP 204. Never creates a cart. */
    fun cart(memberId: Long): Optional<Cart>
}
