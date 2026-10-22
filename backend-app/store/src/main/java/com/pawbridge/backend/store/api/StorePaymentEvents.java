package com.pawbridge.backend.store.api;

import java.util.Map;

/** Store order operations called directly by Payment; activation is owned by Boot. */
public interface StorePaymentEvents {
    /** Outcome of applying a provider-approved payment to the locked Store order. */
    enum Completion { APPLIED, ALREADY_PAID, REJECTED }

    /**
     * Requires the caller's active Store writer transaction. Locks the order row, then marks a PENDING order with
     * the same total amount PAID. The daily ranking increment runs only after that transaction commits.
     */
    Completion completePayment(String orderUuid, long amount);

    /** Requires the caller's active Store writer transaction. Cancels and restores stock only for a locked PENDING order. */
    boolean failPayment(String orderUuid);

    /** Original mock payment operation in its own Store writer transaction. */
    void processPayment(long orderId);

    /** Must restore through the existing locked Store stock/snapshot business in the same writer transaction. */
    @FunctionalInterface interface StockRestorer { void increaseStocks(Map<Long,Integer> quantitiesBySkuId); }
    /** Original Redis sorted-set increment; member is product ID and amount is item quantity. */
    @FunctionalInterface interface Ranking { void increment(String key, String productId, double quantity); }
}
