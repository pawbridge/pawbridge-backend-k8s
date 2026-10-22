package com.pawbridge.backend.payment.internal.confirmation.web;

import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Original route and bare response; verified principal replaces the old gateway-supplied identity header. */
@RestController
public final class PaymentController {
    private final Payments payments;
    public PaymentController(Payments payments) { this.payments = payments; }

    @PostMapping("/api/v1/payments/confirm")
    public ResponseEntity<TossPaymentResponse> confirmPayment(@AuthenticationPrincipal Long verifiedUserId,
            @Valid @RequestBody TossPaymentConfirmRequest request) {
        if (verifiedUserId == null) throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");
        return ResponseEntity.ok(payments.confirmPayment(verifiedUserId, request));
    }
}
