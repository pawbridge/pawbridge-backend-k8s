package com.pawbridge.backend.store.internal.directorder.application;

public interface CartOrders {
    record Command(String receiverName,String receiverPhone,String deliveryAddress,String deliveryMessage) { }
    DirectOrders.OrderDetails create(long verifiedUserId,Command command);
}
