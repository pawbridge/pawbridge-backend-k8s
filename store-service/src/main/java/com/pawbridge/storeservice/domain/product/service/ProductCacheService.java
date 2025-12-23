package com.pawbridge.storeservice.domain.product.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Product Cache Service
 * - 상품 상세 페이지 캐시 관리
 * - Redis 기반 캐시 무효화
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private final StringRedisTemplate redisTemplate;
    
    private static final String CACHE_KEY_PREFIX = "productDetails::";

    /**
     * 상품 상세 캐시 무효화
     * - 재고 변경, 상품 수정/삭제 시 호출
     * @param productId 상품 ID
     */
    public void evictProductCache(Long productId) {
        try {
            String cacheKey = CACHE_KEY_PREFIX + productId;
            Boolean deleted = redisTemplate.delete(cacheKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info(">>> [CACHE EVICT] ProductId: {} 캐시 삭제 완료", productId);
            } else {
                log.debug(">>> [CACHE EVICT] ProductId: {} 캐시 없음 (이미 만료됨)", productId);
            }
        } catch (Exception e) {
            log.error("캐시 무효화 실패: productId={}", productId, e);
            // 캐시 실패는 치명적이지 않으므로 예외 전파하지 않음
        }
    }

    /**
     * 여러 상품 캐시 일괄 무효화
     * @param productIds 상품 ID 목록
     */
    public void evictProductCaches(Iterable<Long> productIds) {
        for (Long productId : productIds) {
            evictProductCache(productId);
        }
    }
}
