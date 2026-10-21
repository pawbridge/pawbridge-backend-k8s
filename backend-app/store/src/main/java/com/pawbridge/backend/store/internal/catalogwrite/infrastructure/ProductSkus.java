package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;
import com.pawbridge.backend.store.internal.catalogwrite.web.SkuCreateDto;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.SKUValueRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Original SKU creation; called only inside the catalog writer transaction. */
final class ProductSkus {
    private final ProductSKURepository productSKURepository;
    private final SKUValueRepository skuValueRepository;
    private final OptionValueRepository optionValueRepository;
    ProductSkus(ProductSKURepository skus,SKUValueRepository values,OptionValueRepository options){
        this.productSKURepository=skus;this.skuValueRepository=values;this.optionValueRepository=options;
    }
    public List<ProductSKU> createSkus(Product product, List<SkuCreateDto> skuDtos) {
        List<ProductSKU> savedSkus = new ArrayList<>();
        
        if (skuDtos == null || skuDtos.isEmpty()) {
            return savedSkus;
        }

        List<Long> optionValueIds = skuDtos.stream()
                .filter(skuDto -> skuDto.getOptionValueIds() != null)
                .flatMap(skuDto -> skuDto.getOptionValueIds().stream())
                .distinct()
                .sorted()
                .toList();
        Map<Long, OptionValue> optionValuesById = optionValueIds.isEmpty()
                ? Map.of()
                : optionValueRepository.findAllByIdWithLock(optionValueIds).stream()
                        .collect(Collectors.toMap(OptionValue::getId, Function.identity()));
        if (optionValuesById.size() != optionValueIds.size()) {
            throw new IllegalArgumentException("존재하지 않는 옵션 값이 포함되어 있습니다.");
        }
        
        for (SkuCreateDto skuDto : skuDtos) {
            // SKU 저장
            ProductSKU sku = ProductSKU.builder()
                    .product(product)
                    .skuCode(skuDto.getSkuCode())
                    .price(skuDto.getPrice())
                    .stockQuantity(skuDto.getStockQuantity())
                    .build();
            productSKURepository.save(sku);
            
            savedSkus.add(sku);
            product.getSkus().add(sku);

            // 옵션 연결 (ID 기반)
            if (skuDto.getOptionValueIds() != null && !skuDto.getOptionValueIds().isEmpty()) {
                for (Long optionValueId : skuDto.getOptionValueIds()) {
                    OptionValue optionValue = optionValuesById.get(optionValueId);
                    
                    SKUValue skuValue = SKUValue.builder()
                            .productSKU(sku)
                            .optionValue(optionValue)
                            .build();
                    skuValueRepository.save(skuValue);
                    sku.getSkuValues().add(skuValue);
                }
            }
        }
        
        return savedSkus;
    }
}
