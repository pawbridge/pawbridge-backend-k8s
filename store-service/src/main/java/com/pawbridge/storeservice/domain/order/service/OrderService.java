package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.order.dto.DirectOrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;

public interface OrderService {
    OrderResponse createOrder(Long userId, OrderCreateRequest request);
    OrderResponse createDirectOrder(Long userId, DirectOrderCreateRequest request);
    OrderResponse getOrder(Long orderId);
    OrderResponse getOrderByUuid(String orderUuid);
    void processPayment(Long orderId);
    void cancelOrder(String orderUuid);
}
