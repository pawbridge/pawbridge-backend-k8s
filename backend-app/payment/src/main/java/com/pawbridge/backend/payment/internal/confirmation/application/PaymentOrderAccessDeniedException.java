package com.pawbridge.backend.payment.internal.confirmation.application;

/** The verified member does not own the order; raised before the provider is contacted. */
public final class PaymentOrderAccessDeniedException extends RuntimeException {
    public PaymentOrderAccessDeniedException() { super("Order does not belong to the member"); }
}
