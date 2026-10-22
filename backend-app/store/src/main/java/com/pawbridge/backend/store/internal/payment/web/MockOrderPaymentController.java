package com.pawbridge.backend.store.internal.payment.web;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Original mock payment route: no owner, amount or provider check, kept as-is behind its own flag. */
@RestController
public final class MockOrderPaymentController {
    private final StorePaymentEvents payments;
    public MockOrderPaymentController(StorePaymentEvents payments) { this.payments=payments; }
    @PostMapping("/api/v1/orders/{orderId}/payment")
    public ResponseEntity<Void> processPayment(@PathVariable("orderId") Long orderId) {
        payments.processPayment(orderId);
        return ResponseEntity.ok().build();
    }
}
