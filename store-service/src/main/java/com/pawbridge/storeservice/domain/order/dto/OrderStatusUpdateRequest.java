package com.pawbridge.storeservice.domain.order.dto;

import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 상태 변경 요청 DTO (관리자용)
 */
@Getter
@NoArgsConstructor
public class OrderStatusUpdateRequest {
    private OrderStatus status;
}
