package com.pawbridge.storeservice.domain.order.dto;

import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderItemResponse {
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
