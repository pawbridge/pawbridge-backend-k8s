package com.pawbridge.backend.payment.internal.confirmation.application.dto

import java.time.OffsetDateTime

class TossPaymentResponse() {
    var paymentKey: String? = null
        private set

    var orderId: String? = null
        private set

    var mId: String? = null
        private set

    var currency: String? = null
        private set

    var method: String? = null // 카드, 계좌이체 등
        private set

    var totalAmount: Long? = null
        private set

    var balanceAmount: Long? = null
        private set

    var status: String? = null // READY, DONE, CANCELED, ABORTED
        private set

    var requestedAt: OffsetDateTime? = null
        private set

    var approvedAt: OffsetDateTime? = null
        private set

    var failure: TossFailure? = null // 결제 실패 시 정보
        private set

    constructor(
        paymentKey: String?,
        orderId: String?,
        mId: String?,
        currency: String?,
        method: String?,
        totalAmount: Long?,
        balanceAmount: Long?,
        status: String?,
        requestedAt: OffsetDateTime?,
        approvedAt: OffsetDateTime?,
        failure: TossFailure?,
    ) : this() {
        this.paymentKey = paymentKey
        this.orderId = orderId
        this.mId = mId
        this.currency = currency
        this.method = method
        this.totalAmount = totalAmount
        this.balanceAmount = balanceAmount
        this.status = status
        this.requestedAt = requestedAt
        this.approvedAt = approvedAt
        this.failure = failure
    }

    override fun toString(): String =
        "TossPaymentResponse(paymentKey=$paymentKey, orderId=$orderId, mId=$mId, currency=$currency, " +
            "method=$method, totalAmount=$totalAmount, balanceAmount=$balanceAmount, status=$status, " +
            "requestedAt=$requestedAt, approvedAt=$approvedAt, failure=$failure)"

    class TossPaymentResponseBuilder internal constructor() {
        private var paymentKey: String? = null
        private var orderId: String? = null
        private var mId: String? = null
        private var currency: String? = null
        private var method: String? = null
        private var totalAmount: Long? = null
        private var balanceAmount: Long? = null
        private var status: String? = null
        private var requestedAt: OffsetDateTime? = null
        private var approvedAt: OffsetDateTime? = null
        private var failure: TossFailure? = null

        fun paymentKey(paymentKey: String?): TossPaymentResponseBuilder = apply {
            this.paymentKey = paymentKey
        }

        fun orderId(orderId: String?): TossPaymentResponseBuilder = apply {
            this.orderId = orderId
        }

        fun mId(mId: String?): TossPaymentResponseBuilder = apply {
            this.mId = mId
        }

        fun currency(currency: String?): TossPaymentResponseBuilder = apply {
            this.currency = currency
        }

        fun method(method: String?): TossPaymentResponseBuilder = apply {
            this.method = method
        }

        fun totalAmount(totalAmount: Long?): TossPaymentResponseBuilder = apply {
            this.totalAmount = totalAmount
        }

        fun balanceAmount(balanceAmount: Long?): TossPaymentResponseBuilder = apply {
            this.balanceAmount = balanceAmount
        }

        fun status(status: String?): TossPaymentResponseBuilder = apply {
            this.status = status
        }

        fun requestedAt(requestedAt: OffsetDateTime?): TossPaymentResponseBuilder = apply {
            this.requestedAt = requestedAt
        }

        fun approvedAt(approvedAt: OffsetDateTime?): TossPaymentResponseBuilder = apply {
            this.approvedAt = approvedAt
        }

        fun failure(failure: TossFailure?): TossPaymentResponseBuilder = apply {
            this.failure = failure
        }

        fun build(): TossPaymentResponse = TossPaymentResponse(
            paymentKey,
            orderId,
            mId,
            currency,
            method,
            totalAmount,
            balanceAmount,
            status,
            requestedAt,
            approvedAt,
            failure,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): TossPaymentResponseBuilder = TossPaymentResponseBuilder()
    }
}
