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
    private Long totalAmount;
    private String status;
    private String deliveryAddress;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .orderId(order.getId())
                .orderUuid(order.getOrderUuid())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .deliveryAddress(order.getDeliveryAddress())
                .createdAt(order.getCreatedAt())
                .items(order.getOrderItems().stream()
                        .map(OrderItemResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }

    @Getter
    @Builder
    public static class OrderItemResponse {
        private String productName;
        private String skuCode;
        private Long price;
        private Integer quantity;

        public static OrderItemResponse from(OrderItem item) {
            return OrderItemResponse.builder()
                    .productName(item.getProductName())
                    .skuCode(item.getSkuCode())
                    .price(item.getPrice())
                    .quantity(item.getQuantity())
                    .build();
        }
    }
}
