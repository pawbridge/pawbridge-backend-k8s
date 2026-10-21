package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
@Builder
public class SkuDetailDto {
    private Long skuId;
    private String skuCode;
    private Long price;
    private Integer stockQuantity;
    private Map<String, String> options; // 예: "Color" -> "Red"

    public static SkuDetailDto from(ProductSKU sku) {
        Map<String, String> optionMap = new HashMap<>();
        for (SKUValue skuValue : sku.getSkuValues()) {
            optionMap.put(
                skuValue.getOptionValue().getOptionGroup().getName(),
                skuValue.getOptionValue().getName()
            );
        }

        return SkuDetailDto.builder()
                .skuId(sku.getId())
                .skuCode(sku.getSkuCode())
                .price(sku.getPrice())
                .stockQuantity(sku.getStockQuantity())
                .options(optionMap)
                .build();
    }
}
