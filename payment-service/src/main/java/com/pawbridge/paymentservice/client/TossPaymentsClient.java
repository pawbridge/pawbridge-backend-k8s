package com.pawbridge.paymentservice.client;

import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentConfirmRequest;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "toss-payments", url = "https://api.tosspayments.com/v1")
public interface TossPaymentsClient {

    @PostMapping("/payments/confirm")
    TossPaymentResponse confirmPayment(
            @RequestHeader("Authorization") String authorization,
            @RequestBody TossPaymentConfirmRequest request
    );
}
