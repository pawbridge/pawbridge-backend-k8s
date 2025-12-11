package com.pawbridge.storeservice.domain.cart.scheduler;

import com.pawbridge.storeservice.domain.cart.entity.Cart;
import com.pawbridge.storeservice.domain.cart.entity.CartItem;
import com.pawbridge.storeservice.domain.cart.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartSyncScheduler {

    private final RedissonClient redissonClient;
    private final CartRepository cartRepository;

    private static final String CART_KEY_PREFIX = "cart:";
    private static final String DIRTY_USERS_KEY = "cart:dirty-users";

    @Scheduled(fixedDelay = 10000) // Run every 10 seconds
    @Transactional
    public void syncCartsToDb() {
        RSet<Long> dirtySet = redissonClient.getSet(DIRTY_USERS_KEY);
        
        if (dirtySet.isEmpty()) {
            return;
        }

        log.debug("Found dirty carts: {}", dirtySet.size());

        // Process each dirty user
        // Note: Iterator gives snapshot-like view, but concurrent modification is handled by Redis logic
        Iterator<Long> iterator = dirtySet.iterator();
        while (iterator.hasNext()) {
            Long userId = iterator.next();
            try {
                processUserCart(userId);
                // Remove from dirty set ONLY after successful processing
                dirtySet.remove(userId);
            } catch (Exception e) {
                log.error("Failed to sync cart for userId: {}", userId, e);
                // Do NOT remove from dirty set, so it retries next time
            }
        }
    }

    private void processUserCart(Long userId) {
        RMap<Long, Integer> redisCart = redissonClient.getMap(CART_KEY_PREFIX + userId);
        Map<Long, Integer> itemsMap = redisCart.readAllMap();

        // Fetch or Create DB Cart
        Cart dbCart = cartRepository.findByUserId(userId)
                .orElseGet(() -> cartRepository.save(new Cart(userId)));

        if (itemsMap.isEmpty()) {
            dbCart.clearItems();
        } else {
            // Apply Sync Logic using Overwrite Strategy
            updateDbCartItems(dbCart, itemsMap);
        }
        
        // Dirty checking triggers SQL on transaction commit
    }

    private void updateDbCartItems(Cart dbCart, Map<Long, Integer> redisItems) {
        // 1. Update Existing or Add New
        redisItems.forEach((skuId, qty) -> {
            Optional<CartItem> existingItem = dbCart.getItems().stream()
                    .filter(item -> item.getSkuId().equals(skuId))
                    .findFirst();

            if (existingItem.isPresent()) {
                existingItem.get().updateQuantity(qty);
            } else {
                dbCart.addItem(new CartItem(skuId, qty));
            }
        });

        // 2. Remove items not in Redis
        dbCart.getItems().removeIf(item -> !redisItems.containsKey(item.getSkuId()));
    }
}
