package com.pawbridge.backend.store.internal.orderadministration.application;

public final class AdminOrderNotFoundException extends RuntimeException {
    public AdminOrderNotFoundException() {super("Order not found");}
}
