package com.pawbridge.storeservice.domain.order.controller;

import com.pawbridge.storeservice.domain.order.dto.DirectOrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import com.pawbridge.storeservice.domain.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
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

    /**
     * 주문 상세 조회 (본인 주문만)
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long orderId) {
        OrderResponse response = orderService.getOrder(orderId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 내 주문 내역 조회 (페이징)
     */
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> getMyOrders(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<OrderResponse> response = orderService.getOrdersByUserId(userId, status, pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/payment")
    public ResponseEntity<Void> processPayment(@PathVariable Long orderId) {
        orderService.processPayment(orderId);
        return ResponseEntity.ok().build();
    }
}

