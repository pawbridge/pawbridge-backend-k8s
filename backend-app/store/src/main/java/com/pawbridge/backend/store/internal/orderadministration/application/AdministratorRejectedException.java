package com.pawbridge.backend.store.internal.orderadministration.application;

public final class AdministratorRejectedException extends RuntimeException {
    public AdministratorRejectedException() {super("Current member cannot administer orders");}
}
