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
    private List<OptionGroupCreateDto> optionGroups;
    private List<SkuCreateDto> skus;
}
