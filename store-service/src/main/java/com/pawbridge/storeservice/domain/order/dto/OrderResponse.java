package com.pawbridge.storeservice.domain.order.dto;

import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class OrderResponse {
    private Long orderId;
    private String orderUuid;
    private Long userId;
    private Long totalAmount;
    private String status;
    private String deliveryStatus;
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;
    private String deliveryMessage;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .orderId(order.getId())
                .orderUuid(order.getOrderUuid())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .deliveryStatus(order.getDeliveryStatus().name())
                .deliveryAddress(order.getDeliveryAddress())
                .receiverName(order.getReceiverName())
                .receiverPhone(order.getReceiverPhone())
                .deliveryMessage(order.getDeliveryMessage())
                .createdAt(order.getCreatedAt())
                .items(order.getOrderItems().stream()
                        .map(OrderItemResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
