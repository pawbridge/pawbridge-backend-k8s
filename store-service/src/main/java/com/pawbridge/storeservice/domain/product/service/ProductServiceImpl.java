package com.pawbridge.storeservice.domain.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductEventPayload;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.entity.*;
import com.pawbridge.storeservice.domain.product.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Override
    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        // 1. Save Product
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .status(ProductStatus.ACTIVE) // Default status
                .build();
        productRepository.save(product);

        // Map to lookup OptionValue entities by "GroupName:ValueName"
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
                    optionValueMap.put(groupDto.getName() + ":" + valName, value);
                }
            }
        }

        // Variables for aggregation (retained for safety/future use, though logic changed)
        List<ProductSKU> tempSavedSkus = new ArrayList<>();

        // 3. Save SKUs & Link with OptionValues
        if (request.getSkus() != null && !request.getSkus().isEmpty()) {
            for (ProductCreateRequest.SkuDto skuDto : request.getSkus()) {
                // 3-1. Save SKU
                ProductSKU sku = ProductSKU.builder()
                        .product(product)
                        .skuCode(skuDto.getSkuCode())
                        .price(skuDto.getPrice())
                        .stockQuantity(skuDto.getStockQuantity())
                        .build();
                productSKURepository.save(sku);
                
                // Add to temporary list for Outbox generation
                tempSavedSkus.add(sku);

                // 3-2. Link Options (SKUValue)
                if (skuDto.getOptions() != null) {
                    for (Map.Entry<String, String> entry : skuDto.getOptions().entrySet()) {
                        String key = entry.getKey() + ":" + entry.getValue();
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

        // 4. Create Outbox Events (SKU per Document)
        // Use locally collected SKUs
        if (!tempSavedSkus.isEmpty()) {
            // Find logic: Lowest Price -> if same, Lower ID
            ProductSKU primarySku = tempSavedSkus.stream()
                .min((s1, s2) -> {
                    int priceCompare = s1.getPrice().compareTo(s2.getPrice());
                    if (priceCompare != 0) return priceCompare;
                    // ID might be null if not flushed yet? usually save() sets ID for Identity strategy but let's be safe
                    if (s1.getId() == null || s2.getId() == null) return 0; 
                    return s1.getId().compareTo(s2.getId());
                })
                .orElse(tempSavedSkus.get(0));

            for (ProductSKU sku : tempSavedSkus) {
                boolean isPrimary = (sku == primarySku); // Object identity check is safe here since we are using same list instances
                
                ProductEventPayload eventPayload = ProductEventPayload.builder()
                        .skuId(sku.getId())
                        .productId(product.getId())
                        .productName(product.getName())
                        .skuCode(sku.getSkuCode())
                        .optionName("") // TODO: Fetch option names
                        .price(sku.getPrice())
                        .stockQuantity(sku.getStockQuantity())
                        .isPrimarySku(isPrimary)
                        .status(product.getStatus().name())
                        .imageUrl(product.getImageUrl())
                        .createdAt(product.getCreatedAt())
                        .updatedAt(product.getUpdatedAt())
                        .build();

                try {
                    String payload = objectMapper.writeValueAsString(eventPayload);
                    Outbox outbox = Outbox.builder()
                            .aggregateType("PRODUCT_SKU")
                            .aggregateId(String.valueOf(sku.getId()))
                            .eventType("SKU_UPDATED")
                            .payload(payload)
                            .build();
                    outboxRepository.save(outbox);
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize SKU event payload", e);
                    throw new RuntimeException("Failed to create outbox event", e);
                }
            }
        }
        
        return response;
    }
}