package com.pawbridge.backend.store.internal.directorder.web;

import com.pawbridge.backend.store.internal.directorder.application.CartOrders;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class CartOrderController {
    private final CartOrders orders;
    public CartOrderController(CartOrders orders) {this.orders=orders;}
    @PostMapping("/api/v1/orders")
    public ResponseEntity<DirectOrders.OrderDetails> create(@AuthenticationPrincipal Long user,@RequestBody CartOrderCreateRequest request) {
        if(user==null) throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");
        return ResponseEntity.status(201).body(orders.create(user,new CartOrders.Command(request.getReceiverName(),request.getReceiverPhone(),request.getDeliveryAddress(),request.getDeliveryMessage())));
    }
}
