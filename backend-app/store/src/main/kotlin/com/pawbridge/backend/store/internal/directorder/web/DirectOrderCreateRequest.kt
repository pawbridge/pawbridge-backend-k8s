package com.pawbridge.backend.store.internal.directorder.web

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

class DirectOrderCreateRequest {
    @field:NotNull
    @field:Positive
    var skuId: Long? = null
        private set

    @field:NotNull
    @field:Positive
    var quantity: Int? = null
        private set

    var receiverName: String? = null
        private set

    var receiverPhone: String? = null
        private set

    var deliveryAddress: String? = null
        private set

    var deliveryMessage: String? = null
        private set
}
