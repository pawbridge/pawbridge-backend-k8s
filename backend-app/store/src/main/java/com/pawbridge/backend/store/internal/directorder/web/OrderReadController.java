package com.pawbridge.backend.store.internal.directorder.web;

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class OrderReadController {
    private final DirectOrders orders;
    public OrderReadController(DirectOrders orders) { this.orders=orders; }
    @GetMapping("/api/v1/orders/{orderId}")
    public ResponseEntity<DirectOrders.OrderDetails> get(@AuthenticationPrincipal Long current,@PathVariable("orderId") long orderId) {
        return ResponseEntity.ok(orders.get(current(current),orderId));
    }
    private static long current(Long member) {
        if(member==null) throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");
        return member;
    }
}
