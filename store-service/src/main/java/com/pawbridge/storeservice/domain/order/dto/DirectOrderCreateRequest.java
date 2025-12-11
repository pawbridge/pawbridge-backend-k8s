package com.pawbridge.storeservice.domain.order.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DirectOrderCreateRequest {
    private Long skuId;
    private Integer quantity;
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;
    private String deliveryMessage;
}
