package com.pawbridge.backend.store.api

/** Payment-facing UUID lookup; Payment checks the owner, amount and state before contacting the provider. */
interface StorePaymentOrders {
    @JvmRecord
    data class Order(
        val orderId: Long?,
        val orderUuid: String?,
        val userId: Long?,
        val totalAmount: Long?,
        val status: String?,
    )

    fun getOrder(orderUuid: String?): Order
}
