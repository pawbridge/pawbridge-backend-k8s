package com.pawbridge.storeservice.domain.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.entity.*;
import com.pawbridge.storeservice.domain.product.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductSKURepository productSKURepository;
    private final SKUValueRepository skuValueRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        // 1. Save Product
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .build();
        productRepository.save(product);

        // Map to lookup OptionValue entities by "GroupName:ValueName"
        // Key: "Color:Red", Value: OptionValueEntity
        Map<String, OptionValue> optionValueMap = new HashMap<>();

        // 2. Save OptionGroups & OptionValues
        if (request.getOptionGroups() != null) {
            for (ProductCreateRequest.OptionGroupDto groupDto : request.getOptionGroups()) {
                OptionGroup group = OptionGroup.builder()
                        .product(product)
                        .name(groupDto.getName())
                        .build();
                optionGroupRepository.save(group);

                for (String valName : groupDto.getValues()) {
                    OptionValue value = OptionValue.builder()
                            .optionGroup(group)
                            .name(valName)
                            .build();
                    optionValueRepository.save(value);

                    // Put into map for SKU linking later
                    String key = groupDto.getName() + ":" + valName;
                    optionValueMap.put(key, value);
                }
            }
        }

        // 3. Save SKUs & Link with OptionValues
        if (request.getSkus() != null) {
            for (ProductCreateRequest.SkuDto skuDto : request.getSkus()) {
                // 3-1. Save SKU
                ProductSKU sku = ProductSKU.builder()
                        .product(product)
                        .skuCode(skuDto.getSkuCode())
                        .price(skuDto.getPrice())
                        .stockQuantity(skuDto.getStockQuantity())
                        .build();
                productSKURepository.save(sku);

                // 3-2. Link Options (SKUValue)
                if (skuDto.getOptions() != null) {
                    for (Map.Entry<String, String> entry : skuDto.getOptions().entrySet()) {
                        String groupName = entry.getKey();
                        String valName = entry.getValue();
                        String key = groupName + ":" + valName;

                        OptionValue optionValue = optionValueMap.get(key);
                        if (optionValue == null) {
                            throw new IllegalArgumentException("Invalid option value in SKU: " + key);
                        }

                        SKUValue skuValue = SKUValue.builder()
                                .productSKU(sku)
                                .optionValue(optionValue)
                                .build();
                        skuValueRepository.save(skuValue);
                    }
                }
            }
        }

        ProductResponse response = ProductResponse.from(product);

        // 4. Save Event to Outbox (For Debezium -> Elasticsearch)
        try {
            String payload = objectMapper.writeValueAsString(response);
            Outbox outbox = Outbox.builder()
                    .aggregateType("PRODUCT")
                    .aggregateId(String.valueOf(product.getId()))
                    .eventType("PRODUCT_CREATED")
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize product response for outbox", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }

        return response;
    }
}
