package com.pawbridge.backend.payment.internal.confirmation.application;

/** The order is not PENDING; raised before the provider is contacted. */
public final class PaymentOrderNotPayableException extends RuntimeException {
    public PaymentOrderNotPayableException() { super("Order is not in PENDING state"); }
}
