package com.pawbridge.backend.store.internal.orderadministration.application

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders

interface AdminOrders {

    @JvmRecord
    data class Query(
        val status: String?,
        val deliveryStatus: String?,
        val userId: Long?,
        val keyword: String?,
        val sortBy: String?,
        val sortOrder: String?,
        val page: Int,
        val size: Int,
    )

    @JvmRecord
    data class Page(
        val content: List<DirectOrders.OrderDetails>?,
        val number: Int,
        val size: Int,
        val totalElements: Long,
        val sortBy: String?,
        val ascending: Boolean,
    )

    fun list(verifiedAdministratorId: Long, query: Query): Page

    fun get(verifiedAdministratorId: Long, orderId: Long): DirectOrders.OrderDetails

    fun updateDelivery(
        verifiedAdministratorId: Long,
        orderId: Long,
        deliveryStatus: String?,
    ): DirectOrders.OrderDetails

    fun updateStatus(
        verifiedAdministratorId: Long,
        orderId: Long,
        status: String?,
    ): DirectOrders.OrderDetails
}
