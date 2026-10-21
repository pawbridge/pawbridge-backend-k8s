package com.pawbridge.backend.store.api;

/** Original Payment-facing UUID lookup, without a new ownership or order-state policy. */
public interface StorePaymentOrders {
    record Order(Long orderId, String orderUuid, Long totalAmount, String status) { }
    Order getOrder(String orderUuid);
}
