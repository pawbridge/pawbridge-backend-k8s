package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/** HTTP mapping from the original Feign contract. Boot owns endpoint and transport activation gates. */
class HttpTossPaymentsClient(private val client: RestClient) : TossPaymentsClient {

    override fun confirmPayment(authorization: String, request: TossPaymentConfirmRequest): TossPaymentResponse? =
        client.post()
            .uri("/payments/confirm")
            .header("Authorization", authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(TossPaymentResponse::class.java)

    override fun cancelPayment(
        authorization: String,
        paymentKey: String,
        request: TossPaymentCancelRequest,
    ): TossPaymentResponse? =
        client.post()
            .uri("/payments/{paymentKey}/cancel", paymentKey)
            .header("Authorization", authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(TossPaymentResponse::class.java)

    override fun getPayment(authorization: String, paymentKey: String): TossPaymentResponse? =
        client.get()
            .uri("/payments/{paymentKey}", paymentKey)
            .header("Authorization", authorization)
            .retrieve()
            .body(TossPaymentResponse::class.java)
}
