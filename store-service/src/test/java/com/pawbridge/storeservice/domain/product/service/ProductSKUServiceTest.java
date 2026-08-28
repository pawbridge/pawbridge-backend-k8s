package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.SkuCreateDto;
import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.ProductStatus;
import com.pawbridge.storeservice.domain.product.repository.OptionValueRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.repository.SKUValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductSKUServiceTest {

    @Mock
    private ProductSKURepository productSKURepository;
    @Mock
    private SKUValueRepository skuValueRepository;
    @Mock
    private OptionValueRepository optionValueRepository;

    private ProductSKUService productSKUService;

    @BeforeEach
    void setUp() {
        productSKUService = new ProductSKUService(
                productSKURepository,
                skuValueRepository,
                optionValueRepository
        );
    }

    @Test
    void givenDifferentPrices_whenFindingPrimarySku_thenSelectsLowestPrice() {
        ProductSKU expensiveSku = sku(20_000L);
        ProductSKU cheapSku = sku(10_000L);

        ProductSKU primarySku = productSKUService.findPrimarySku(List.of(expensiveSku, cheapSku));

        assertSame(cheapSku, primarySku);
    }

    @Test
    void givenSamePrice_whenFindingPrimarySku_thenSelectsLowestSkuId() {
        ProductSKU higherIdSku = sku(10_000L);
        ProductSKU lowerIdSku = sku(10_000L);
        when(higherIdSku.getId()).thenReturn(2L);
        when(lowerIdSku.getId()).thenReturn(1L);

        ProductSKU primarySku = productSKUService.findPrimarySku(List.of(higherIdSku, lowerIdSku));

        assertSame(lowerIdSku, primarySku);
    }

    @Test
    void givenUnorderedOptionIds_whenCreatingSkus_thenLocksAllOptionValuesInIdOrderFirst() {
        Product product = Product.builder().name("사료").status(ProductStatus.ACTIVE).build();
        ReflectionTestUtils.setField(product, "id", 10L);
        OptionGroup group = OptionGroup.builder().name("크기").build();
        OptionValue first = OptionValue.builder().optionGroup(group).name("소").build();
        OptionValue second = OptionValue.builder().optionGroup(group).name("대").build();
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(second, "id", 2L);
        SkuCreateDto sku = new SkuCreateDto();
        ReflectionTestUtils.setField(sku, "skuCode", "FOOD-L");
        ReflectionTestUtils.setField(sku, "price", 10_000L);
        ReflectionTestUtils.setField(sku, "stockQuantity", 3);
        ReflectionTestUtils.setField(sku, "optionValueIds", List.of(2L, 1L));
        when(optionValueRepository.findAllByIdWithLock(List.of(1L, 2L))).thenReturn(List.of(first, second));

        productSKUService.createSkus(product, List.of(sku));

        verify(optionValueRepository).findAllByIdWithLock(List.of(1L, 2L));
    }

    private ProductSKU sku(Long price) {
        ProductSKU sku = org.mockito.Mockito.mock(ProductSKU.class);
        when(sku.getPrice()).thenReturn(price);
        return sku;
    }
}
