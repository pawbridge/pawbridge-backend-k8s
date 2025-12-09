package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;

public interface OrderService {
    OrderResponse createOrder(Long userId, OrderCreateRequest request);
    OrderResponse getOrder(Long orderId);
    void processPayment(Long orderId);
}
