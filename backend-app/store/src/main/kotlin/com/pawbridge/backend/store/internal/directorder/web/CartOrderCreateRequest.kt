package com.pawbridge.backend.store.internal.directorder.web

class CartOrderCreateRequest {
    var receiverName: String? = null
        private set

    var receiverPhone: String? = null
        private set

    var deliveryAddress: String? = null
        private set

    var deliveryMessage: String? = null
        private set
}
