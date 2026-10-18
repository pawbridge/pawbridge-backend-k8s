package com.pawbridge.storeservice.domain.product.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductOutboxServiceTest {

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private ProductSKUService productSKUService;

    private ObjectMapper objectMapper;
    private ProductOutboxService productOutboxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        productOutboxService = new ProductOutboxService(
                outboxRepository,
                objectMapper,
                productSKUService
        );
    }

    @Test
    void givenMultipleSkus_whenPublishingSnapshot_thenWritesStableKeysAndOnePrimary() throws Exception {
        Product product = Product.builder()
                .name("사료")
                .status(ProductStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(product, "id", 10L);
        ReflectionTestUtils.setField(product, "createdAt", LocalDateTime.of(2026, 8, 28, 10, 0));

        ProductSKU primarySku = ProductSKU.builder()
                .product(product)
                .skuCode("FOOD-S")
                .price(10_000L)
                .stockQuantity(2)
                .build();
        ProductSKU otherSku = ProductSKU.builder()
                .product(product)
                .skuCode("FOOD-L")
                .price(20_000L)
                .stockQuantity(3)
                .build();
        ReflectionTestUtils.setField(primarySku, "id", 101L);
        ReflectionTestUtils.setField(otherSku, "id", 102L);
        product.getSkus().addAll(List.of(primarySku, otherSku));
        when(productSKUService.findPrimarySku(product.getSkus())).thenReturn(primarySku);

        productOutboxService.publishProductSnapshot(product);

        ArgumentCaptor<Outbox> outboxCaptor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository, org.mockito.Mockito.times(2)).save(outboxCaptor.capture());
        List<Outbox> events = outboxCaptor.getAllValues();

        assertEquals(List.of("101", "102"), events.stream().map(Outbox::getAggregateId).toList());
        assertTrue(events.stream().allMatch(event -> "product-sku".equals(event.getAggregateType())));

        JsonNode primaryPayload = objectMapper.readTree(events.get(0).getPayload());
        JsonNode otherPayload = objectMapper.readTree(events.get(1).getPayload());
        assertTrue(primaryPayload.get("isPrimarySku").asBoolean());
        assertFalse(otherPayload.get("isPrimarySku").asBoolean());
        assertEquals("ACTIVE", primaryPayload.get("status").asText());
        assertEquals(5, primaryPayload.get("totalStockQuantity").asInt());
        assertEquals(5, otherPayload.get("totalStockQuantity").asInt());
        assertEquals(primaryPayload.get("updatedAt"), otherPayload.get("updatedAt"));
    }

    @Test
    void givenSoftDeletedProduct_whenPublishingSnapshot_thenWritesFullDeletedDocument() throws Exception {
        Product product = Product.builder()
                .name("사료")
                .status(ProductStatus.DELETED)
                .build();
        ReflectionTestUtils.setField(product, "id", 10L);
        ReflectionTestUtils.setField(product, "createdAt", LocalDateTime.of(2026, 8, 28, 10, 0));
        ProductSKU sku = ProductSKU.builder()
                .product(product)
                .skuCode("FOOD-S")
                .price(10_000L)
                .stockQuantity(0)
                .build();
        ReflectionTestUtils.setField(sku, "id", 101L);
        product.getSkus().add(sku);
        when(productSKUService.findPrimarySku(product.getSkus())).thenReturn(sku);

        productOutboxService.publishProductSnapshot(product);

        ArgumentCaptor<Outbox> outboxCaptor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        JsonNode payload = objectMapper.readTree(outboxCaptor.getValue().getPayload());
        assertEquals("DELETED", payload.get("status").asText());
        assertTrue(payload.get("isPrimarySku").asBoolean());
        assertFalse(payload.has("deleted"));
    }
}
