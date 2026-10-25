package com.pawbridge.backend.store.internal.directorder.application

interface CartOrders {

    @JvmRecord
    data class Command(
        val receiverName: String?,
        val receiverPhone: String?,
        val deliveryAddress: String?,
        val deliveryMessage: String?,
    )

    fun create(verifiedUserId: Long, command: Command): DirectOrders.OrderDetails
}
