package com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem

class OrderItemResponse internal constructor(
    val productName: String?,
    val skuCode: String?,
    val price: Long?,
    val quantity: Int?,
) {

    companion object {
        @JvmStatic
        fun from(item: OrderItem): OrderItemResponse =
            OrderItemResponse(
                productName = item.productName,
                skuCode = item.skuCode,
                price = item.price,
                quantity = item.quantity,
            )
    }
}
