package com.pawbridge.backend.store.internal.cart.application;

import java.util.List;

public interface Carts {
    record Item(Long id, Long skuId, String skuCode, String productName, Long price, Integer quantity, Long totalPrice) { }
    List<Item> get(long verifiedUserId);
    void add(long verifiedUserId, Long skuId, Integer quantity);
    void update(long verifiedUserId, long skuId, int quantity);
    void remove(long verifiedUserId, long skuId);
    void clear(long verifiedUserId);
}
