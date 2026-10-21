package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Selected original stock business; locks remain held until the caller's writer transaction completes. */
public final class JpaStockAdjustments {
    private final ProductRepository products;
    private final ProductSKURepository skus;
    private final ProductSnapshots snapshots;
    public JpaStockAdjustments(ProductRepository products,ProductSKURepository skus,ProductSnapshots snapshots) {
        this.products=products;this.skus=skus;this.snapshots=snapshots;
    }
    void decreaseStocks(Map<Long,Integer> quantities) { adjustStocks(quantities,true); }
    public void increaseStocks(Map<Long,Integer> quantities) { adjustStocks(quantities,false); }
    private void adjustStocks(Map<Long,Integer> quantities,boolean decrease) {
        if(quantities==null || quantities.isEmpty()) return;
        quantities.forEach((id,quantity)->{
            if(id==null || quantity==null || quantity<=0) throw new IllegalArgumentException("SKU ID and quantity must be positive");
        });
        var references=skus.findProductIdsBySkuIdIn(quantities.keySet());
        if(references.size()!=quantities.size()) throw new IllegalArgumentException("A requested SKU does not exist");
        Map<Long,List<Long>> byProduct=new HashMap<>();
        references.forEach(reference->byProduct.computeIfAbsent(reference.getProductId(),ignored->new ArrayList<>()).add(reference.getSkuId()));
        byProduct.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry->adjustProductStocks(entry.getKey(),entry.getValue(),quantities,decrease));
    }
    private void adjustProductStocks(Long productId,List<Long> targets,Map<Long,Integer> quantities,boolean decrease) {
        Product product=products.findByIdWithLock(productId).orElseThrow(()->new IllegalArgumentException("Product not found"));
        List<ProductSKU> locked=skus.findAllByProductIdWithLock(productId);
        Map<Long,ProductSKU> byId=locked.stream().collect(Collectors.toMap(ProductSKU::getId,sku->sku));
        targets.stream().sorted(Comparator.naturalOrder()).forEach(id->{
            ProductSKU sku=Optional.ofNullable(byId.get(id)).orElseThrow(()->new IllegalArgumentException("SKU not found after product lock"));
            if(decrease && product.getStatus()!=ProductStatus.ACTIVE) throw new IllegalStateException("Product cannot be ordered in its current state");
            if(decrease) sku.decreaseStock(quantities.get(id));else sku.increaseStock(quantities.get(id));
        });
        snapshots.publishProductSnapshot(product);
        // Approved difference: this module has no Redis product cache consumer; eviction is an explicit no-op.
    }
}
