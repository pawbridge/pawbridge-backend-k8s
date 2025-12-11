package com.pawbridge.storeservice.domain.order.controller;

import com.pawbridge.storeservice.domain.order.dto.DirectOrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/direct")
    public ResponseEntity<OrderResponse> createDirectOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody DirectOrderCreateRequest request) {
        OrderResponse response = orderService.createDirectOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/uuid/{orderUuid}")
    public ResponseEntity<OrderResponse> getOrderByUuid(@PathVariable String orderUuid) {
        OrderResponse response = orderService.getOrderByUuid(orderUuid);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long orderId) {
        OrderResponse response = orderService.getOrder(orderId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/payment")
    public ResponseEntity<Void> processPayment(@PathVariable Long orderId) {
        orderService.processPayment(orderId);
        return ResponseEntity.ok().build();
    }
}
