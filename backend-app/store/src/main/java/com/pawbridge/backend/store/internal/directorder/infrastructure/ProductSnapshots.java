package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import java.util.List;

/** Original ProductOutboxService search snapshot and ProductSKUService.findPrimarySku; the SKU_UPDATED outbox was removed with the event queue. */
public final class ProductSnapshots {
    private final PostgresqlSearchDocuments documents;
    public ProductSnapshots(org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations search,
            com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms terms) {
        this(new PostgresqlSearchDocuments(search,terms));
    }
    ProductSnapshots(PostgresqlSearchDocuments documents) {
        this.documents=documents;
    }
    public void publishProductSnapshot(Product product) {
        if(product.getSkus().isEmpty()) { documents.delete(product.getId());return; }
        ProductSKU primary=findPrimarySku(product.getSkus());
        documents.write(product.getId(),primary.getId(),product.getName(),primary.generateOptionName());
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
