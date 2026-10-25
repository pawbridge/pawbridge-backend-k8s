package com.pawbridge.backend.store.internal.orderadministration.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus

class OrderStatusUpdateRequest {
    var status: OrderStatus? = null
        private set
}
