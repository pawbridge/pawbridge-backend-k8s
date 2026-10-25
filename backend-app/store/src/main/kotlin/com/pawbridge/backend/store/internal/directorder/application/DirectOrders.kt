package com.pawbridge.backend.store.internal.directorder.application

import com.pawbridge.backend.store.api.StoreMemberReads
import java.time.LocalDateTime

interface DirectOrders {

    @JvmRecord
    data class Command(
        val skuId: Long?,
        val quantity: Int?,
        val receiverName: String?,
        val receiverPhone: String?,
        val deliveryAddress: String?,
        val deliveryMessage: String?,
    )

    @JvmRecord
    data class Item(
        val productName: String?,
        val skuCode: String?,
        val price: Long?,
        val quantity: Int?,
    )

    @JvmRecord
    data class OrderDetails(
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
        val items: List<Item>?,
    )

    fun create(verifiedUserId: Long, command: Command): OrderDetails

    fun get(verifiedUserId: Long, orderId: Long): OrderDetails

    fun list(
        verifiedUserId: Long,
        status: String?,
        request: StoreMemberReads.Request,
    ): StoreMemberReads.Page<OrderDetails>
}
