package com.pawbridge.storeservice.domain.order.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class OrderCreateRequest {
    private String deliveryAddress;
    private List<OrderItemDto> items;

    @Getter
    @NoArgsConstructor
    public static class OrderItemDto {
        private Long skuId;
        private Integer quantity;
    }
}
