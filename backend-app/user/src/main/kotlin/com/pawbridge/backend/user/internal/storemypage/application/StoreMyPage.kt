package com.pawbridge.backend.user.internal.storemypage.application

import java.time.LocalDateTime

/** The existing User facade for Store-owned, read-only MyPage data. */
interface StoreMyPage {

    @JvmRecord
    data class Request(
        val page: Int,
        val size: Int,
        val sort: String?,
    )

    @JvmRecord
    data class Order(
        val property: String,
        val ascending: Boolean,
        val ignoreCase: Boolean,
        val nullHandling: String?,
    )

    @JvmRecord
    data class Page<T>(
        val content: List<T>?,
        val number: Int,
        val size: Int,
        val totalElements: Long,
        val sort: List<Order>?,
    )

    @JvmRecord
    data class Wishlist(
        val wishlistId: Long?,
        val productId: Long?,
        val productName: String?,
        val productDescription: String?,
        val productImageUrl: String?,
        val productStatus: String?,
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
        val items: List<OrderItem>?,
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
        val items: List<CartItem>?,
        val createdAt: LocalDateTime?,
    )

    fun wishlists(currentUserId: Long, request: Request): Page<Wishlist>

    fun orders(currentUserId: Long, request: Request): Page<Purchase>

    fun cart(currentUserId: Long): Cart?
}
