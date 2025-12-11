package com.pawbridge.paymentservice.domain.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class StoreOrderResponse {
    private Long orderId;
    private String orderUuid;
    private Long totalAmount;
    private String status;
}
