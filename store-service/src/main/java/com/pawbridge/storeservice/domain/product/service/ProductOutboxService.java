package com.pawbridge.storeservice.domain.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.product.dto.ProductEventPayload;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Product Outbox Service
 * - SKU 이벤트를 Outbox 테이블에 저장
 * - Debezium CDC로 Kafka → Elasticsearch 연동
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductOutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * SKU 생성/수정 이벤트 발행
     * @param product 상품 엔티티
     * @param sku SKU 엔티티
     * @param isPrimary 대표 SKU 여부
     */
    public void publishSkuEvent(Product product, ProductSKU sku, boolean isPrimary) {
        ProductEventPayload eventPayload = buildEventPayload(product, sku, isPrimary);
        saveOutboxEvent(sku.getId(), "SKU_UPDATED", eventPayload);
    }

    /**
     * SKU 삭제 이벤트 발행
     * @param skuId 삭제된 SKU ID
     */
    public void publishSkuDeleteEvent(Long skuId) {
        try {
            // 삭제 이벤트는 ID만 포함
            String payload = objectMapper.writeValueAsString(
                java.util.Map.of("skuId", skuId, "deleted", true)
            );
            
            Outbox outbox = Outbox.builder()
                    .aggregateType("PRODUCT_SKU")
                    .aggregateId(String.valueOf(skuId))
                    .eventType("SKU_DELETED")
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
            
            log.info(">>> [OUTBOX] SKU 삭제 이벤트 발행: skuId={}", skuId);
        } catch (JsonProcessingException e) {
            log.error("SKU 삭제 이벤트 페이로드 직렬화 실패: skuId={}", skuId, e);
            throw new RuntimeException("Outbox 이벤트 생성 실패", e);
        }
    }

    /**
     * 이벤트 페이로드 빌드
     */
    private ProductEventPayload buildEventPayload(Product product, ProductSKU sku, boolean isPrimary) {
        return ProductEventPayload.builder()
                .skuId(sku.getId())
                .productId(product.getId())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .productName(product.getName())
                .skuCode(sku.getSkuCode())
                .optionName(sku.generateOptionName())
                .price(sku.getPrice())
                .stockQuantity(sku.getStockQuantity())
                .isPrimarySku(isPrimary)
                .status(product.getStatus().name())
                .imageUrl(product.getImageUrl())
                .createdAt(product.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Outbox 테이블에 이벤트 저장
     */
    private void saveOutboxEvent(Long skuId, String eventType, ProductEventPayload payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            Outbox outbox = Outbox.builder()
                    .aggregateType("PRODUCT_SKU")
                    .aggregateId(String.valueOf(skuId))
                    .eventType(eventType)
                    .payload(payloadJson)
                    .build();
            outboxRepository.save(outbox);
            
            log.debug(">>> [OUTBOX] 이벤트 발행: {}, skuId={}", eventType, skuId);
        } catch (JsonProcessingException e) {
            log.error("SKU 이벤트 페이로드 직렬화 실패: skuId={}", skuId, e);
            throw new RuntimeException("Outbox 이벤트 생성 실패", e);
        }
    }
}
