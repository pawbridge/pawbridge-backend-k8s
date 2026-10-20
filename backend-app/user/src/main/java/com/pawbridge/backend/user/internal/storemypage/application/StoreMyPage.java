package com.pawbridge.backend.user.internal.storemypage.application;

import java.time.LocalDateTime;
import java.util.List;

/** The existing User facade for Store-owned, read-only MyPage data. */
public interface StoreMyPage {
    record Request(int page, int size, String sort) { }
    record Order(String property, boolean ascending, boolean ignoreCase, String nullHandling) { }
    record Page<T>(List<T> content, int number, int size, long totalElements, List<Order> sort) { }
    record Wishlist(Long wishlistId, Long productId, String productName, String productDescription,
                    String productImageUrl, String productStatus, Long categoryId, String categoryName,
                    LocalDateTime createdAt) { }
    record OrderItem(Long orderItemId, Long productSkuId, String productName, String skuCode,
                     Long price, Integer quantity) { }
    record Purchase(Long orderId, String orderUuid, Long totalAmount, String status, String deliveryAddress,
                    String deliveryStatus, List<OrderItem> items, LocalDateTime createdAt) { }
    record CartItem(Long cartItemId, Long productSkuId, String productName, String skuCode,
                    Long price, Integer quantity, Long totalPrice) { }
    record Cart(Long cartId, Long userId, List<CartItem> items, LocalDateTime createdAt) { }

    Page<Wishlist> wishlists(long currentUserId, Request request);
    Page<Purchase> orders(long currentUserId, Request request);
    Cart cart(long currentUserId);
}
