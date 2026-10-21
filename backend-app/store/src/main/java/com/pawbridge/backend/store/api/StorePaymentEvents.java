package com.pawbridge.backend.store.api;

import java.util.Map;

/** Original Store payment consumer and order operations; activation/transport are owned by Boot. */
public interface StorePaymentEvents {
    void handle(String event);
    void processPayment(long orderId);
    void cancelOrder(String orderUuid);

    /** Must restore through the existing locked Store stock/snapshot business in the same writer transaction. */
    @FunctionalInterface interface StockRestorer { void increaseStocks(Map<Long,Integer> quantitiesBySkuId); }
    /** Original Redis sorted-set increment; member is product ID and amount is item quantity. */
    @FunctionalInterface interface Ranking { void increment(String key, String productId, double quantity); }
}
