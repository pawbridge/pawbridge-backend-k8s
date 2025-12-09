package com.pawbridge.storeservice.domain.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.dto.ProductEventPayload; // Added import
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductSKURepository productSKURepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    @Transactional
    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        String orderUuid = UUID.randomUUID().toString();
        
        // 1. 주문 생성
        Order order = Order.builder()
                .orderUuid(orderUuid)
                .userId(userId)
                .deliveryAddress(request.getDeliveryAddress())
                .totalAmount(0L)
                .build();

        long totalAmount = 0L;

        // 2. 상품 아이템 처리 (비관적 락 사용)
        for (OrderCreateRequest.OrderItemDto itemDto : request.getItems()) {
            ProductSKU sku = productSKURepository.findByIdWithLock(itemDto.getSkuId())
                    .orElseThrow(() -> new IllegalArgumentException("SKU를 찾을 수 없습니다: " + itemDto.getSkuId()));

            // 재고 차감
            sku.decreaseStock(itemDto.getQuantity());

            // 주문 아이템 생성
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .productSKU(sku)
                    .productName(sku.getProduct().getName())
                    .skuCode(sku.getSkuCode())
                    .price(sku.getPrice())
                    .quantity(itemDto.getQuantity())
                    .build();
            
            order.getOrderItems().add(orderItem);
            totalAmount += sku.getPrice() * itemDto.getQuantity();

            // [New] 재고 업데이트 이벤트 발행 (Elasticsearch 동기화)
            publishStockUpdateEvent(sku);
        }

        // 3. 주문 확정
        order.updateTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);
        
        OrderResponse response = OrderResponse.from(savedOrder);

        // 4. Outbox 이벤트 저장 (ORDER_CREATED)
        try {
            String payload = objectMapper.writeValueAsString(response);
            Outbox outbox = Outbox.builder()
                    .aggregateType("ORDER")
                    .aggregateId(orderUuid)
                    .eventType("ORDER_CREATED")
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to create outbox event for order", e);
            throw new RuntimeException("Order created but failed to save event", e);
        }

        return response;
    }

    private void publishStockUpdateEvent(ProductSKU sku) {
        // [Cache Eviction] 재고 변경 시 상품 상세 캐시 제거
        evictProductCache(sku.getProduct().getId());

        ProductEventPayload eventPayload = ProductEventPayload.builder()
                .skuId(sku.getId())
                .productId(sku.getProduct().getId())
                .productName(sku.getProduct().getName())
                .skuCode(sku.getSkuCode())
                .optionName(sku.generateOptionName()) // 리팩토링된 메서드 사용
                .price(sku.getPrice())
                .stockQuantity(sku.getStockQuantity())
                // 임시: 대표 SKU 여부를 여기서 계산하려면 쿼리 비용이 큼.
                // Elasticsearch는 부분 업데이트(Partial Update)가 아니라 전체 문서 덮어쓰기(Upsert) 방식일 가능성이 큼.
                // 현재 MVP 단계에서는 'false'로 보내도 검색 결과에 큰 영향이 없거나, 인덱싱 쪽에서 처리한다고 가정.
                // 추후 정확한 정합성을 위해 별도 로직이 필요할 수 있음.
                .isPrimarySku(false) 
                .status(sku.getProduct().getStatus().name())
                .imageUrl(sku.getProduct().getImageUrl())
                .createdAt(sku.getProduct().getCreatedAt())
                .updatedAt(sku.getProduct().getUpdatedAt())
                .build();

        try {
            String payload = objectMapper.writeValueAsString(eventPayload);
            Outbox outbox = Outbox.builder()
                    .aggregateType("PRODUCT_SKU")
                    .aggregateId(String.valueOf(sku.getId()))
                    .eventType("SKU_STOCK_DEDUCTED") // 재고 차감 전용 이벤트 타입
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to create stock update event", e);
            // Don't fail the order for this, just log error? Or fail? 
            // Better to fail to ensure consistency.
            throw new RuntimeException("Failed to save stock update event", e);
        }
    }

    private void evictProductCache(Long productId) {
        try {
            Cache cache = cacheManager.getCache("productDetails");
            if (cache != null) {
                cache.evict(productId);
                log.info("Evicted productDetails cache for productId: {}", productId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict cache for productId: {}", productId, e);
        }
    }
}