package com.pawbridge.backend.store.internal.orderadministration.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus

class DeliveryStatusUpdateRequest {
    var deliveryStatus: DeliveryStatus? = null
        private set
}
