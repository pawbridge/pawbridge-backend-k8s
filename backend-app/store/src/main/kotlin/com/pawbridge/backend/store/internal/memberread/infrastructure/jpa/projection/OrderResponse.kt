package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.projection

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import java.time.LocalDateTime

/**
 * 주문 내역 응답 DTO
 * - 마이페이지용
 */
data class OrderResponse(
    var orderId: Long? = null,
    var orderUuid: String? = null,
    var totalAmount: Long? = null,
    var status: String? = null,
    var deliveryAddress: String? = null,
    var deliveryStatus: String? = null,
    var items: List<OrderItemDto>? = null,
    var createdAt: LocalDateTime? = null,
) {

    /**
     * 주문 항목 DTO (내부 클래스)
     */
    data class OrderItemDto(
        var orderItemId: Long? = null,
        var productSkuId: Long? = null,
        var productName: String? = null,
        var skuCode: String? = null,
        var price: Long? = null,
        var quantity: Int? = null,
    )

    companion object {
        @JvmStatic
        fun from(order: Order): OrderResponse {
            val itemDtos = order.orderItems.map { item ->
                OrderItemDto(
                    orderItemId = item.id,
                    productSkuId = item.productSKU!!.id,
                    productName = item.productName,
                    skuCode = item.skuCode,
                    price = item.price,
                    quantity = item.quantity,
                )
            }

            return OrderResponse(
                orderId = order.id,
                orderUuid = order.orderUuid,
                totalAmount = order.totalAmount,
                status = order.status!!.name,
                deliveryAddress = order.deliveryAddress,
                deliveryStatus = order.deliveryStatus!!.name,
                items = itemDtos,
                createdAt = order.createdAt,
            )
        }
    }
}
