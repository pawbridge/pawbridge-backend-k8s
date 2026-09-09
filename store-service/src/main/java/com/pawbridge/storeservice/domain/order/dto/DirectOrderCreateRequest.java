package com.pawbridge.storeservice.domain.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DirectOrderCreateRequest {
    @NotNull
    @Positive
    private Long skuId;
    @NotNull
    @Positive
    private Integer quantity;
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;
    private String deliveryMessage;
}
