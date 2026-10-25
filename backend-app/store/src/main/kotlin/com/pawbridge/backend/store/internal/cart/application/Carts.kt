package com.pawbridge.backend.store.internal.cart.application

interface Carts {

    @JvmRecord
    data class Item(
        val id: Long?,
        val skuId: Long?,
        val skuCode: String?,
        val productName: String?,
        val price: Long?,
        val quantity: Int?,
        val totalPrice: Long?,
    )

    fun get(verifiedUserId: Long): List<Item>

    fun add(verifiedUserId: Long, skuId: Long?, quantity: Int?)

    fun update(verifiedUserId: Long, skuId: Long, quantity: Int)

    fun remove(verifiedUserId: Long, skuId: Long)

    fun clear(verifiedUserId: Long)
}
