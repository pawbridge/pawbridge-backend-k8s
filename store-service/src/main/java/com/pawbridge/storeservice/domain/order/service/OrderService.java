package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.order.dto.DirectOrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderService {
    OrderResponse createOrder(Long userId, OrderCreateRequest request);
    OrderResponse createDirectOrder(Long userId, DirectOrderCreateRequest request);
    OrderResponse getOrder(Long orderId, Long userId);
    OrderResponse getOrderByUuid(String orderUuid);
    Page<OrderResponse> getOrdersByUserId(Long userId, OrderStatus status, Pageable pageable);
    void processPayment(Long orderId);
    void cancelOrder(String orderUuid);
}

