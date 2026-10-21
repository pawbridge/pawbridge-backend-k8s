package com.pawbridge.backend.store.internal.directorder.application;

import com.pawbridge.backend.store.api.StoreMemberReads;
import java.time.LocalDateTime;
import java.util.List;

public interface DirectOrders {
    record Command(Long skuId, Integer quantity, String receiverName, String receiverPhone,
                   String deliveryAddress, String deliveryMessage) { }
    record Item(String productName, String skuCode, Long price, Integer quantity) { }
    record OrderDetails(Long orderId, String orderUuid, Long userId, Long totalAmount, String status,
                        String deliveryStatus, String receiverName, String receiverPhone, String deliveryAddress,
                        String deliveryMessage, LocalDateTime createdAt, List<Item> items) { }
    OrderDetails create(long verifiedUserId, Command command);
    OrderDetails get(long verifiedUserId, long orderId);
    StoreMemberReads.Page<OrderDetails> list(long verifiedUserId, String status, StoreMemberReads.Request request);
}
