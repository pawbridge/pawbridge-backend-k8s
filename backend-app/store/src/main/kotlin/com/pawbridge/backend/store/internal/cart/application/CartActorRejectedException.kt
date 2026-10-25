package com.pawbridge.backend.store.internal.cart.application

class CartActorRejectedException : RuntimeException("Current member cannot access carts")
