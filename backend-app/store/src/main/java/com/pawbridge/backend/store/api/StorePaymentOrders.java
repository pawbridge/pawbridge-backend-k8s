package com.pawbridge.backend.store.api;

/** Payment-facing UUID lookup; Payment checks the owner, amount and state before contacting the provider. */
public interface StorePaymentOrders {
    record Order(Long orderId, String orderUuid, Long userId, Long totalAmount, String status) { }
    Order getOrder(String orderUuid);
}
