package com.pawbridge.backend.store.internal.directorder.application;

public final class OrderActorRejectedException extends RuntimeException {
    public OrderActorRejectedException() { super("Current member cannot access orders"); }
}
