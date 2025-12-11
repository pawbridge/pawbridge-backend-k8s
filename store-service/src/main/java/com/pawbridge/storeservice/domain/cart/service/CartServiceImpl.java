package com.pawbridge.storeservice.domain.cart.service;

import com.pawbridge.storeservice.domain.cart.dto.CartAddRequest;
import com.pawbridge.storeservice.domain.cart.dto.CartItemResponse;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
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
    private static final String DIRTY_USERS_KEY = "cart:dirty-users";

    private RMap<Long, Integer> getCartMap(Long userId) {
        return redissonClient.getMap(CART_KEY_PREFIX + userId);
    }

    private void markAsDirty(Long userId) {
        RSet<Long> dirtySet = redissonClient.getSet(DIRTY_USERS_KEY);
        dirtySet.add(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public void addToCart(Long userId, CartAddRequest request) {
        // 1. Validation
        ProductSKU sku = productSKURepository.findById(request.getSkuId())
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + request.getSkuId()));
        
        if (sku.getStockQuantity() < request.getQuantity()) {
            throw new IllegalArgumentException("Not enough stock. Current: " + sku.getStockQuantity());
        }

        // 2. Redis Update (Hot Data)
        RMap<Long, Integer> cartMap = getCartMap(userId);
        cartMap.addAndGet(request.getSkuId(), request.getQuantity());

        // 3. Mark for Async Sync
        markAsDirty(userId);
        
        log.info("Added to cart (Redis only). UserId: {}, SkuId: {}", userId, request.getSkuId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartItemResponse> getMyCart(Long userId) {
        RMap<Long, Integer> cartMap = getCartMap(userId);
        Map<Long, Integer> itemMap = cartMap.readAllMap();

        if (itemMap.isEmpty()) {
            return Collections.emptyList();
        }

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
        
        RMap<Long, Integer> cartMap = getCartMap(userId);
        if (cartMap.containsKey(skuId)) {
            cartMap.put(skuId, quantity); 
            markAsDirty(userId); // Mark
            log.info("Updated cart qty. UserId: {}, SkuId: {}, NewQty: {}", userId, skuId, quantity);
        } else {
             throw new IllegalArgumentException("Item not in cart");
        }
    }

    @Override
    public void removeCartItem(Long userId, Long skuId) {
        RMap<Long, Integer> cartMap = getCartMap(userId);
        cartMap.remove(skuId);
        markAsDirty(userId); // Mark
        log.info("Removed from cart. UserId: {}, SkuId: {}", userId, skuId);
    }

    @Override
    public void clearCart(Long userId) {
        RMap<Long, Integer> cartMap = getCartMap(userId);
        cartMap.delete();
        markAsDirty(userId); // Mark (Sync will clear DB too)
        log.info("Cleared cart for UserId: {}", userId);
    }

    @Override
    public void resetSystem() {
        // [Admin] 장바구니 관련 모든 Redis 키 삭제 (데이터 포맷 변경 시 사용)
        redissonClient.getKeys().deleteByPattern("cart:*");
        log.info("Reset all cart data in Redis (System Reset)");
    }
}
