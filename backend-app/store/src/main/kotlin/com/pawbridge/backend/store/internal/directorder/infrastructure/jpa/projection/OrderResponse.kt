package com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import java.time.LocalDateTime
import java.util.stream.Collectors

class OrderResponse internal constructor(
    val orderId: Long?,
    val orderUuid: String?,
    val userId: Long?,
    val totalAmount: Long?,
    val status: String?,
    val deliveryStatus: String?,
    val receiverName: String?,
    val receiverPhone: String?,
    val deliveryAddress: String?,
    val deliveryMessage: String?,
    val createdAt: LocalDateTime?,
    val items: List<OrderItemResponse>?,
) {

    companion object {
        // Arguments are evaluated in the order the Lombok builder chain used, so a missing status fails the same way.
        @JvmStatic
        fun from(order: Order): OrderResponse =
            OrderResponse(
                orderId = order.id,
                orderUuid = order.orderUuid,
                userId = order.userId,
                totalAmount = order.totalAmount,
                status = order.status!!.name,
                deliveryStatus = order.deliveryStatus!!.name,
                deliveryAddress = order.deliveryAddress,
                receiverName = order.receiverName,
                receiverPhone = order.receiverPhone,
                deliveryMessage = order.deliveryMessage,
                createdAt = order.createdAt,
                items = order.orderItems.stream()
                    .map { item -> OrderItemResponse.from(item) }
                    .collect(Collectors.toList()),
            )
    }
}
