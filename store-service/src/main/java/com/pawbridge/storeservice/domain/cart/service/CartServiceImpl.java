package com.pawbridge.storeservice.domain.cart.service;

import com.pawbridge.storeservice.domain.cart.dto.CartAddRequest;
import com.pawbridge.storeservice.domain.cart.dto.CartItemResponse;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final RedissonClient redissonClient;
    private final ProductSKURepository productSKURepository;

    private static final String CART_KEY_PREFIX = "cart:";

    // Cart: Hash<SkuId (Long), Quantity (Integer)>
    private RMap<Long, Integer> getCartMap(Long userId) {
        return redissonClient.getMap(CART_KEY_PREFIX + userId);
    }

    @Override
    @Transactional(readOnly = true) // Validates SKU from DB
    public void addToCart(Long userId, CartAddRequest request) {
        // 1. Validation
        ProductSKU sku = productSKURepository.findById(request.getSkuId())
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + request.getSkuId()));
        
        if (sku.getStockQuantity() < request.getQuantity()) {
            throw new IllegalArgumentException("Not enough stock. Current: " + sku.getStockQuantity());
        }

        // 2. Redis Update
        RMap<Long, Integer> cart = getCartMap(userId);
        
        // Key: SkuId, Value: Quantity
        // Add quantity if exists (Atomic)
        cart.addAndGet(request.getSkuId(), request.getQuantity());
        
        log.info("Added to cart. UserId: {}, SkuId: {}, Qty: {}", userId, request.getSkuId(), request.getQuantity());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartItemResponse> getMyCart(Long userId) {
        RMap<Long, Integer> cart = getCartMap(userId);
        Map<Long, Integer> itemMap = cart.readAllMap(); // Fetch all items

        if (itemMap.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. Enrich with Product Info
        Set<Long> skuIds = itemMap.keySet();
        List<ProductSKU> skus = productSKURepository.findAllById(skuIds);

        return skus.stream()
                .map(sku -> {
                    Integer qty = itemMap.get(sku.getId());
                    return CartItemResponse.of(sku, qty != null ? qty : 0);
                })
                .collect(Collectors.toList());
    }

    @Override
    public void updateQuantity(Long userId, Long skuId, int quantity) {
        if (quantity <= 0) {
            removeCartItem(userId, skuId);
            return;
        }
        
        RMap<Long, Integer> cart = getCartMap(userId);
        if (cart.containsKey(skuId)) {
            cart.put(skuId, quantity); // Overwrite
            log.info("Updated cart qty. UserId: {}, SkuId: {}, NewQty: {}", userId, skuId, quantity);
        } else {
             throw new IllegalArgumentException("Item not in cart");
        }
    }

    @Override
    public void removeCartItem(Long userId, Long skuId) {
        RMap<Long, Integer> cart = getCartMap(userId);
        cart.remove(skuId);
        log.info("Removed from cart. UserId: {}, SkuId: {}", userId, skuId);
    }

    @Override
    public void clearCart(Long userId) {
        RMap<Long, Integer> cart = getCartMap(userId);
        cart.delete();
        log.info("Cleared cart for UserId: {}", userId);
    }
}
