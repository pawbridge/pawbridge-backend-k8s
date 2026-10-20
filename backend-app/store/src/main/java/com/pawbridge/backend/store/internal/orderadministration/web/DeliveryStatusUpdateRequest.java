package com.pawbridge.backend.store.internal.orderadministration.web;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DeliveryStatusUpdateRequest {
    private DeliveryStatus deliveryStatus;
}
