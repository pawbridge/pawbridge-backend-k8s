package com.pawbridge.backend.store.internal.orderadministration.web;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class OrderStatusUpdateRequest {
    private OrderStatus status;
}
