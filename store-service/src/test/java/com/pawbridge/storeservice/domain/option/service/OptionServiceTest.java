package com.pawbridge.storeservice.domain.option.service;

import com.pawbridge.storeservice.domain.option.dto.OptionGroupRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionValueRequest;
import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.ProductStatus;
import com.pawbridge.storeservice.domain.product.repository.OptionGroupRepository;
import com.pawbridge.storeservice.domain.product.repository.OptionValueRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.service.ProductOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionServiceTest {

    @Mock
    private OptionGroupRepository optionGroupRepository;
    @Mock
    private OptionValueRepository optionValueRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductSKURepository productSKURepository;
    @Mock
    private ProductOutboxService productOutboxService;

    private OptionService optionService;

    @BeforeEach
    void setUp() {
        optionService = new OptionService(
                optionGroupRepository,
                optionValueRepository,
                productRepository,
                productSKURepository,
                productOutboxService
        );
    }

    @Test
    void givenUsedOptionGroup_whenNameUpdated_thenRepublishesAffectedProductSnapshot() {
        OptionGroup group = OptionGroup.builder().name("크기").build();
        ReflectionTestUtils.setField(group, "id", 1L);
        Product product = product();
        ProductSKU sku = sku(product);
        when(optionGroupRepository.findByIdWithLock(1L)).thenReturn(Optional.of(group));
        when(productSKURepository.findProductIdsByOptionGroupId(1L)).thenReturn(List.of(10L));
        when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
        when(productSKURepository.findAllByProductIdWithLock(10L)).thenReturn(List.of(sku));
        OptionGroupRequest request = new OptionGroupRequest();
        ReflectionTestUtils.setField(request, "name", "사이즈");

        optionService.updateOptionGroup(1L, request);

        verify(productOutboxService).publishProductSnapshot(product);
    }

    @Test
    void givenUsedOptionValue_whenNameUpdated_thenRepublishesAffectedProductSnapshot() {
        OptionGroup group = OptionGroup.builder().name("색상").build();
        ReflectionTestUtils.setField(group, "id", 1L);
        OptionValue value = OptionValue.builder().optionGroup(group).name("빨강").build();
        ReflectionTestUtils.setField(value, "id", 2L);
        Product product = product();
        ProductSKU sku = sku(product);
        when(optionValueRepository.findByIdWithLock(2L)).thenReturn(Optional.of(value));
        when(productSKURepository.findProductIdsByOptionValueId(2L)).thenReturn(List.of(10L));
        when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
        when(productSKURepository.findAllByProductIdWithLock(10L)).thenReturn(List.of(sku));
        OptionValueRequest request = new OptionValueRequest();
        ReflectionTestUtils.setField(request, "name", "레드");

        optionService.updateOptionValue(2L, request);

        verify(productOutboxService).publishProductSnapshot(product);
    }

    private Product product() {
        Product product = Product.builder().name("사료").status(ProductStatus.ACTIVE).build();
        ReflectionTestUtils.setField(product, "id", 10L);
        return product;
    }

    private ProductSKU sku(Product product) {
        ProductSKU sku = ProductSKU.builder()
                .product(product)
                .skuCode("FOOD-S")
                .price(10_000L)
                .stockQuantity(1)
                .build();
        ReflectionTestUtils.setField(sku, "id", 101L);
        product.getSkus().add(sku);
        return sku;
    }
}
