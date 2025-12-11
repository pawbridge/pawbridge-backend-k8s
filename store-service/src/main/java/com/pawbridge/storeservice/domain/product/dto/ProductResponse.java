package com.pawbridge.storeservice.domain.product.dto;

import com.pawbridge.storeservice.domain.product.entity.Product;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private String imageUrl;
    private String status;
    private Long categoryId;
    private String categoryName;
    private List<SkuDetailDto> skus;

    public static ProductResponse from(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .imageUrl(product.getImageUrl())
                .status(product.getStatus().name())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .skus(product.getSkus().stream()
                        .map(SkuDetailDto::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
