package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse

/** Existing provider boundary; cancellation and lookup are internal confirmation steps, not public APIs. */
interface TossPaymentsClient {
    fun confirmPayment(authorization: String, request: TossPaymentConfirmRequest): TossPaymentResponse?

    fun cancelPayment(authorization: String, paymentKey: String, request: TossPaymentCancelRequest): TossPaymentResponse?

    /** GET /v1/payments/{paymentKey}: used once when the confirm result is unknown (timeout, connection error or 5xx). */
    fun getPayment(authorization: String, paymentKey: String): TossPaymentResponse?
}
