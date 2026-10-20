package com.pawbridge.backend.store.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Store-owned read boundary for a caller that has already authenticated/authorized the member.
 * No member existence check or public HTTP/X-User trust policy is implied by this API.
 * The User module may map these projections into its existing MyPage response without accessing Store internals.
 */
public interface StoreMemberReads {
    /** Native database null order, matching the original Spring Data JPA Order. */
    record Order(String property, boolean ascending, boolean ignoreCase) { }

    /** Paged request; HTTP defaults (page 0, size 20, createdAt DESC) belong to the caller. */
    record Request(int page, int size, List<Order> sort) {
        public Request {
            if(page<0 || size<1) throw new IllegalArgumentException("Page must be non-negative and size positive");
            sort=List.copyOf(sort);
        }
        public long offset() { return (long)page*size; }
    }

    record Page<T>(List<T> content,int number,int size,long totalElements,List<Order> sort) { }
    record Wishlist(Long wishlistId,Long skuId,String skuCode,Long productId,String productName,
                    String productDescription,String productImageUrl,String productStatus,Long price,
                    Map<String,String> options,Long categoryId,String categoryName,LocalDateTime createdAt) { }
    record OrderItem(Long orderItemId,Long productSkuId,String productName,String skuCode,Long price,Integer quantity) { }
    record Purchase(Long orderId,String orderUuid,Long totalAmount,String status,String deliveryAddress,
                    String deliveryStatus,List<OrderItem> items,LocalDateTime createdAt) { }
    record CartItem(Long cartItemId,Long productSkuId,String productName,String skuCode,
                    Long price,Integer quantity,Long totalPrice) { }
    record Cart(Long cartId,Long userId,List<CartItem> items,LocalDateTime createdAt) { }

    /** All wishlist statuses remain visible; includes the original Store SKU/price/options fields. */
    Page<Wishlist> wishlists(long memberId,Request request);
    /** Order items contain purchase snapshots rather than current product/SKU values. */
    Page<Purchase> orders(long memberId,Request request);
    /** Empty means no persisted cart, corresponding to the original Store HTTP 204. Never creates a cart. */
    Optional<Cart> cart(long memberId);
}
