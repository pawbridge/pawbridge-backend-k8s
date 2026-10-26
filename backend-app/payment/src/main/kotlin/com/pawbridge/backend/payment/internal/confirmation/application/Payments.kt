package com.pawbridge.backend.payment.internal.confirmation.application

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse

/** Existing confirmation only; no ready, query, user-cancel or points operation. */
interface Payments {
    fun confirmPayment(verifiedUserId: Long?, request: TossPaymentConfirmRequest): TossPaymentResponse
}
