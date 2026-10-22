package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss;

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** HTTP mapping from the original Feign contract. Boot owns endpoint and transport activation gates. */
public final class HttpTossPaymentsClient implements TossPaymentsClient {
    private final RestClient client;
    public HttpTossPaymentsClient(RestClient client) { this.client = client; }

    @Override public TossPaymentResponse confirmPayment(String authorization, TossPaymentConfirmRequest request) {
        return client.post().uri("/payments/confirm").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(TossPaymentResponse.class);
    }

    @Override public TossPaymentResponse cancelPayment(String authorization, String paymentKey, TossPaymentCancelRequest request) {
        return client.post().uri("/payments/{paymentKey}/cancel", paymentKey).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(TossPaymentResponse.class);
    }

    @Override public TossPaymentResponse getPayment(String authorization, String paymentKey) {
        return client.get().uri("/payments/{paymentKey}", paymentKey).header("Authorization", authorization)
                .retrieve().body(TossPaymentResponse.class);
    }
}
