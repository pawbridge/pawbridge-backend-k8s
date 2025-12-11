package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * store-service에서 받아오는 주문 정보 DTO
 * - store-service의 OrderResponse와 동일한 구조
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
}
