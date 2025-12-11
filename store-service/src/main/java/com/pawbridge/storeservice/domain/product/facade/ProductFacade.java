package com.pawbridge.storeservice.domain.product.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.domain.product.dto.ProductDetailResponse;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductFacade {

    private final ProductService productService;
    private final RedissonClient redissonClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    // CacheConfig에서 설정한 RedisTemplate<String, Object>가 JSON 직렬화를 처리함

    private static final String LOCK_KEY_PREFIX = "lock:productDetails:";
    private static final String CACHE_KEY_PREFIX = "productDetails::";
    private static final long WAIT_TIME = 5L;
    private static final long LEASE_TIME = 3L;

    public ProductDetailResponse getProductDetails(Long productId) {
        String cacheKey = CACHE_KEY_PREFIX + productId;

        // 1. [Cache-Aside] Check Cache
        ProductDetailResponse cached = getFromCache(cacheKey);
        if (cached != null) {
            log.info(">>> [CACHE HIT] ProductId: {}", productId);
            return cached;
        }

        String lockKey = LOCK_KEY_PREFIX + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 2. [Distributed Lock] Try Lock (Wait 5s, Auto-unlock 3s)
            boolean available = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            if (!available) {
                log.warn(">>> [LOCK FAIL] Could not acquire lock for productId: {}", productId);
                return productService.getProductDetails(productId); 
            }

            // 3. [Double-Check] Check Cache again
            cached = getFromCache(cacheKey);
            if (cached != null) {
                log.info(">>> [CACHE HIT - DoubleCheck] ProductId: {}", productId);
                return cached;
            }

            // 4. [DB Load] Call Service
            log.info(">>> [CACHE MISS] Loading from DB... ProductId: {}", productId);
            try {
                // Thread.sleep(3000); // [테스트] 제거됨
            } catch (Exception e) {} 
            ProductDetailResponse response = productService.getProductDetails(productId);

            // 5. [Cache Write] Set to Redis with Jitter TTL
            long baseTtl = 600; // 10 mins
            long jitter = ThreadLocalRandom.current().nextLong(0, 60); // 0~60s random
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(baseTtl + jitter));
            log.info(">>> [CACHE WRITE] Saved to Redis with TTL: {}s", baseTtl + jitter);
            
            return response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock interrupted", e);
        } finally {
            // 6. Unlock
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    // Object를 DTO로 변환하는 헬퍼 메서드
    private ProductDetailResponse getFromCache(String key) {
        try {
            Object data = redisTemplate.opsForValue().get(key);
            if (data instanceof ProductDetailResponse) {
                return (ProductDetailResponse) data;
            } else if (data != null) {
                // GenericJackson2JsonRedisSerializer 사용 시 타입 정보가 없으면 LinkedHashMap으로 반환될 수 있음
                // 하지만 ObjectMapper에 TypeValidator가 설정되어 있다면 클래스 정보가 포함됨
                // 여기서는 적절히 변환된다고 가정
                return objectMapper.convertValue(data, ProductDetailResponse.class);
            }
        } catch (Exception e) {
            log.warn("Cache parsing error", e);
        }
        return null;
    }

    public ProductResponse updateProduct(Long productId, com.pawbridge.storeservice.domain.product.dto.ProductUpdateRequest request) {
        // 1. DB 업데이트 (Transactional)
        ProductResponse response = productService.updateProduct(productId, request);

        // 2. 캐시 무효화 (Eviction)
        try {
            String cacheKey = CACHE_KEY_PREFIX + productId;
            RBucket<Object> bucket = redissonClient.getBucket(cacheKey);
            boolean deleted = bucket.delete();
            if (deleted) {
                log.info(">>> [CACHE EVICT] ProductId: {} (Update API)", productId);
            } else {
                log.info(">>> [CACHE EVICT] Cache not found or already deleted. ProductId: {}", productId);
            }
        } catch (Exception e) {
            log.error("Failed to evict cache for productId: {}", productId, e);
        }

        return response;
    }
}
