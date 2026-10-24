package com.pawbridge.backend.payment.internal.confirmation.application.dto

import jakarta.validation.constraints.Pattern

class TossPaymentConfirmRequest() {
    @field:Pattern(regexp = "[\\u0000-\\uFFFF]*", message = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
    var paymentKey: String? = null
        private set

    @field:Pattern(regexp = "[\\u0000-\\uFFFF]*", message = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
    var orderId: String? = null
        private set

    var amount: Long? = null
        private set

    constructor(paymentKey: String?, orderId: String?, amount: Long?) : this() {
        this.paymentKey = paymentKey
        this.orderId = orderId
        this.amount = amount
    }

    class TossPaymentConfirmRequestBuilder internal constructor() {
        private var paymentKey: String? = null
        private var orderId: String? = null
        private var amount: Long? = null

        fun paymentKey(paymentKey: String?): TossPaymentConfirmRequestBuilder = apply {
            this.paymentKey = paymentKey
        }

        fun orderId(orderId: String?): TossPaymentConfirmRequestBuilder = apply {
            this.orderId = orderId
        }

        fun amount(amount: Long?): TossPaymentConfirmRequestBuilder = apply {
            this.amount = amount
        }

        fun build(): TossPaymentConfirmRequest = TossPaymentConfirmRequest(paymentKey, orderId, amount)
    }

    companion object {
        @JvmStatic
        fun builder(): TossPaymentConfirmRequestBuilder = TossPaymentConfirmRequestBuilder()
    }
}
