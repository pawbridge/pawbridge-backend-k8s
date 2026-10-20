package com.pawbridge.backend.store.internal.cart.application;

public final class CartActorRejectedException extends RuntimeException {
    public CartActorRejectedException() { super("Current member cannot access carts"); }
}
