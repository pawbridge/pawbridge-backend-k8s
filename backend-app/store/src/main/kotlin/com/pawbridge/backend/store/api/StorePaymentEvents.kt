package com.pawbridge.backend.store.api

/** Store order operations called directly by Payment; activation is owned by Boot. */
interface StorePaymentEvents {
    /** Outcome of applying a provider-approved payment to the locked Store order. */
    enum class Completion { APPLIED, ALREADY_PAID, REJECTED }

    /**
     * Result of [completePayment]. `afterCommit` increments the daily ranking; the caller runs it only
     * after its transaction committed and released the connection. It never throws (failures are logged).
     */
    @JvmRecord
    data class Completed(
        val completion: Completion,
        val afterCommit: Runnable,
    )

    /**
     * Requires the caller's active Store writer transaction. Locks the order row, then marks a PENDING order with
     * the same total amount PAID.
     */
    fun completePayment(orderUuid: String?, amount: Long): Completed

    /** Requires the caller's active Store writer transaction. Cancels and restores stock only for a locked PENDING order. */
    fun failPayment(orderUuid: String?): Boolean

    /** Original mock payment operation in its own Store writer transaction. */
    fun processPayment(orderId: Long)

    /** Must restore through the existing locked Store stock/snapshot business in the same writer transaction. */
    fun interface StockRestorer {
        fun increaseStocks(quantitiesBySkuId: Map<Long?, Int>)
    }

    /** Original Redis sorted-set increment; member is product ID and amount is item quantity. */
    fun interface Ranking {
        fun increment(key: String, productId: String, quantity: Double)
    }
}
