package com.pawbridge.backend.store.internal.directorder.web;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class CartOrderCreateRequest {
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;
    private String deliveryMessage;
}
