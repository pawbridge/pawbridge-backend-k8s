package com.pawbridge.backend.store.internal.cart.infrastructure.jpa.projection

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU

class CartItemResponse internal constructor(
    val id: Long?,
    val skuId: Long?,
    val skuCode: String?,
    val productName: String?,
    val price: Long?,
    val quantity: Int?,
    val totalPrice: Long?,
) {

    companion object {
        @JvmStatic
        fun of(sku: ProductSKU, quantity: Int): CartItemResponse =
            CartItemResponse(
                id = null, // Redis Based Cart doesn't have a generated Item ID, or we use SkuId as ID
                skuId = sku.id,
                skuCode = sku.skuCode,
                productName = sku.product!!.name,
                price = sku.price,
                quantity = quantity,
                totalPrice = sku.price!! * quantity,
            )
    }
}
