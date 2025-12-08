package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
public class ProductCreateRequest {
    private String name;
    private String description;
    private String imageUrl;
    private Long categoryId;
    private List<OptionGroupDto> optionGroups;
    private List<SkuDto> skus;

    @Getter
    @NoArgsConstructor
    public static class OptionGroupDto {
        private String name; // e.g., Color
        private List<String> values; // e.g., [Red, Blue]
    }

    @Getter
    @NoArgsConstructor
    public static class SkuDto {
        private String skuCode;
        private Long price;
        private Integer stockQuantity;
        // Key: OptionGroupName (Color), Value: OptionValueName (Red)
        private Map<String, String> options; 
    }
}
