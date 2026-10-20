package com.pawbridge.backend.store.internal.wishlist.application;

import java.time.LocalDateTime;
import java.util.Map;

/** Member identity comes from Root's authenticated caller, never an HTTP body/header userId. */
public interface WishlistCommands {
    record Added(Long wishlistId,Long userId,Long skuId,String skuCode,Long productId,String productName,
                 String productImageUrl,Long price,Map<String,String> options,LocalDateTime createdAt) { }
    Added add(long currentMemberId,Long skuId);
    void removeByMemberAndSku(long currentMemberId,long skuId);
    /** Prepared owner-scoped replacement only; no HTTP handler is supplied until Root accepts the policy change. */
    void removeById(long currentMemberId,long wishlistId);
}
