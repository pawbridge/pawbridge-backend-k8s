package com.pawbridge.backend.store.internal.directorder.web;

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class DirectOrderController {
    private final DirectOrders orders;
    public DirectOrderController(DirectOrders orders) { this.orders=orders; }
    @PostMapping("/api/v1/orders/direct")
    public ResponseEntity<DirectOrders.OrderDetails> create(@AuthenticationPrincipal Long current,@Valid @RequestBody DirectOrderCreateRequest request) {
        return ResponseEntity.status(201).body(orders.create(current(current),new DirectOrders.Command(request.getSkuId(),request.getQuantity(),
                request.getReceiverName(),request.getReceiverPhone(),request.getDeliveryAddress(),request.getDeliveryMessage())));
    }
    private static long current(Long member) {
        if(member==null) throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");
        return member;
    }
}
