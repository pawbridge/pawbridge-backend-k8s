package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss;

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;

/** Existing provider boundary; cancellation and lookup are internal confirmation steps, not public APIs. */
public interface TossPaymentsClient {
    TossPaymentResponse confirmPayment(String authorization, TossPaymentConfirmRequest request);
    TossPaymentResponse cancelPayment(String authorization, String paymentKey, TossPaymentCancelRequest request);
    /** GET /v1/payments/{paymentKey}: used once when the confirm result is unknown (timeout, connection error or 5xx). */
    TossPaymentResponse getPayment(String authorization, String paymentKey);
}
