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

    static final String PRODUCT_SEARCH_AGGREGATE_TYPE = "product-sku";

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ProductSKUService productSKUService;

    /**
     * 한 상품의 검색 projection 전체를 같은 시점의 snapshot으로 발행한다.
     * 대표 SKU는 최저가, 동가이면 낮은 SKU ID 규칙으로 매번 다시 계산한다.
     */
    public void publishProductSnapshot(Product product) {
        if (product.getSkus().isEmpty()) {
            return;
        }

        ProductSKU primarySku = productSKUService.findPrimarySku(product.getSkus());
        int totalStockQuantity = product.getSkus().stream()
                .mapToInt(ProductSKU::getStockQuantity)
                .sum();
        LocalDateTime snapshotUpdatedAt = LocalDateTime.now();

        for (ProductSKU sku : product.getSkus()) {
            ProductEventPayload eventPayload = buildEventPayload(
                    product,
                    sku,
                    sku == primarySku,
                    totalStockQuantity,
                    snapshotUpdatedAt
            );
            saveOutboxEvent(sku.getId(), "SKU_UPDATED", eventPayload);
        }
    }

    /**
     * 이벤트 페이로드 빌드
     */
    private ProductEventPayload buildEventPayload(
            Product product,
            ProductSKU sku,
            boolean isPrimary,
            int totalStockQuantity,
            LocalDateTime snapshotUpdatedAt
    ) {
        return ProductEventPayload.builder()
                .skuId(sku.getId())
                .productId(product.getId())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .productName(product.getName())
                .skuCode(sku.getSkuCode())
                .optionName(sku.generateOptionName())
                .price(sku.getPrice())
                .stockQuantity(sku.getStockQuantity())
                .totalStockQuantity(totalStockQuantity)
                .isPrimarySku(isPrimary)
                .status(product.getStatus().name())
                .imageUrl(product.getImageUrl())
                .createdAt(product.getCreatedAt())
                .updatedAt(snapshotUpdatedAt)
                .build();
    }

    /**
     * Outbox 테이블에 이벤트 저장
     */
    private void saveOutboxEvent(Long skuId, String eventType, ProductEventPayload payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            Outbox outbox = Outbox.builder()
                    .aggregateType(PRODUCT_SEARCH_AGGREGATE_TYPE)
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
