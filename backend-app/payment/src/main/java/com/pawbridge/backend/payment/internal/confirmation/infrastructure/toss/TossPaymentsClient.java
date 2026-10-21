package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss;

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;

/** Existing provider boundary; cancellation is an internal best-effort compensation, not a public API. */
public interface TossPaymentsClient {
    TossPaymentResponse confirmPayment(String authorization, TossPaymentConfirmRequest request);
    TossPaymentResponse cancelPayment(String authorization, String paymentKey, TossPaymentCancelRequest request);
}
