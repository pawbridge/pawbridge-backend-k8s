package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.projection.ProductEventPayload;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** Original ProductOutboxService snapshot and ProductSKUService.findPrimarySku; no cache consumer exists in this module. */
final class ProductSnapshots {
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final PostgresqlSearchDocuments documents;
    private final Clock clock;
    ProductSnapshots(OutboxRepository outbox,ObjectMapper mapper,PostgresqlSearchDocuments documents,Clock clock) {
        this.outbox=outbox;this.mapper=mapper;this.documents=documents;this.clock=clock;
    }
    void publishProductSnapshot(Product product) {
        if(product.getSkus().isEmpty()) { documents.delete(product.getId());return; }
        ProductSKU primary=findPrimarySku(product.getSkus());
        documents.write(product.getId(),primary.getId(),product.getName(),primary.generateOptionName());
        int totalStock=product.getSkus().stream().mapToInt(ProductSKU::getStockQuantity).sum();
        LocalDateTime updated=LocalDateTime.now(clock);
        for(ProductSKU sku:product.getSkus()) {
            ProductEventPayload payload=ProductEventPayload.builder().skuId(sku.getId()).productId(product.getId())
                    .categoryId(product.getCategory()!=null?product.getCategory().getId():null).productName(product.getName())
                    .skuCode(sku.getSkuCode()).optionName(sku.generateOptionName()).price(sku.getPrice())
                    .stockQuantity(sku.getStockQuantity()).totalStockQuantity(totalStock).isPrimarySku(sku==primary)
                    .status(product.getStatus().name()).imageUrl(product.getImageUrl()).createdAt(product.getCreatedAt()).updatedAt(updated).build();
            try {
                outbox.save(Outbox.builder().aggregateType("product-sku").aggregateId(String.valueOf(sku.getId()))
                        .eventType("SKU_UPDATED").payload(mapper.writeValueAsString(payload)).build());
            } catch(JsonProcessingException failure) { throw new IllegalStateException("Outbox event serialization failed",failure); }
        }
    }
    private static ProductSKU findPrimarySku(List<ProductSKU> skus) {
        if(skus==null || skus.isEmpty()) return null;
        return skus.stream().min((first,second)->{
            int compared=first.getPrice().compareTo(second.getPrice());
            if(compared!=0) return compared;
            if(first.getId()==null || second.getId()==null) return 0;
            return first.getId().compareTo(second.getId());
        }).orElse(skus.get(0));
    }
}
