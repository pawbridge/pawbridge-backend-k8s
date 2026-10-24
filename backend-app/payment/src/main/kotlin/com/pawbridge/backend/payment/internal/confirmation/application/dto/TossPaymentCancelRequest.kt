package com.pawbridge.backend.payment.internal.confirmation.application.dto

class TossPaymentCancelRequest() {
    var cancelReason: String? = null
        private set

    constructor(cancelReason: String?) : this() {
        this.cancelReason = cancelReason
    }

    class TossPaymentCancelRequestBuilder internal constructor() {
        private var cancelReason: String? = null

        fun cancelReason(cancelReason: String?): TossPaymentCancelRequestBuilder = apply {
            this.cancelReason = cancelReason
        }

        fun build(): TossPaymentCancelRequest = TossPaymentCancelRequest(cancelReason)
    }

    companion object {
        @JvmStatic
        fun builder(): TossPaymentCancelRequestBuilder = TossPaymentCancelRequestBuilder()
    }
}
