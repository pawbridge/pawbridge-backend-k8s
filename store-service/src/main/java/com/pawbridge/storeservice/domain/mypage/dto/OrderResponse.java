package com.pawbridge.storeservice.domain.mypage.dto;

import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 주문 내역 응답 DTO
 * - 마이페이지용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private Long orderId;
    private String orderUuid;
    private Long totalAmount;
    private String status;
    private String deliveryAddress;
    private String deliveryStatus;
    private List<OrderItemDto> items;
    private LocalDateTime createdAt;

    /**
     * 주문 항목 DTO (내부 클래스)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemDto {
        private Long orderItemId;
        private Long productSkuId;
        private String productName;
        private String skuCode;
        private Long price;
        private Integer quantity;
    }

    public static OrderResponse from(Order order) {
        List<OrderItemDto> itemDtos = order.getOrderItems().stream()
                .map(item -> OrderItemDto.builder()
                        .orderItemId(item.getId())
                        .productSkuId(item.getProductSKU().getId())
                        .productName(item.getProductName())
                        .skuCode(item.getSkuCode())
                        .price(item.getPrice())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        return OrderResponse.builder()
                .orderId(order.getId())
                .orderUuid(order.getOrderUuid())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .deliveryAddress(order.getDeliveryAddress())
                .deliveryStatus(order.getDeliveryStatus().name())
                .items(itemDtos)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
