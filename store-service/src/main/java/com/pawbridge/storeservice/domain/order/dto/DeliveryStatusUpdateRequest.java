package com.pawbridge.storeservice.domain.order.dto;

import com.pawbridge.storeservice.domain.order.entity.DeliveryStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송 상태 변경 요청 DTO (관리자용)
 */
@Getter
@NoArgsConstructor
public class DeliveryStatusUpdateRequest {
    private DeliveryStatus deliveryStatus;
}
