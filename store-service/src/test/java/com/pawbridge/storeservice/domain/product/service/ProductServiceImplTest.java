package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.cart.repository.CartItemRepository;
import com.pawbridge.storeservice.domain.product.dto.ProductUpdateRequest;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.ProductStatus;
import com.pawbridge.storeservice.domain.product.repository.CategoryRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductSKURepository productSKURepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private ProductSKUService productSKUService;
    @Mock
    private ProductOutboxService productOutboxService;
    @Mock
    private ProductCacheService productCacheService;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(
                productRepository,
                categoryRepository,
                productSKURepository,
                cartItemRepository,
                productSKUService,
                productOutboxService,
                productCacheService
        );
    }

    @Test
    void givenProductWithMultipleSkus_whenProductUpdated_thenPublishesWholeProductSnapshot() {
        Product product = productWithTwoSkus();
        when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
        when(productSKURepository.findAllByProductIdWithLock(10L)).thenReturn(product.getSkus());

        productService.updateProduct(10L, new ProductUpdateRequest());

        verify(productOutboxService).publishProductSnapshot(product);
    }

    @Test
    void givenProductWithMultipleSkus_whenStockChanges_thenPublishesWholeProductSnapshot() {
        Product product = productWithTwoSkus();
        when(productSKURepository.findProductIdsBySkuIdIn(Set.of(102L)))
                .thenReturn(List.of(skuProductId(102L, 10L)));
        when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
        when(productSKURepository.findAllByProductIdWithLock(10L)).thenReturn(product.getSkus());

        productService.decreaseStocks(Map.of(102L, 1));

        verify(productOutboxService).publishProductSnapshot(product);
    }

    @Test
    void givenSkusAcrossProducts_whenStockChanges_thenLocksProductsByIdAndPublishesOncePerProduct() {
        Product higherProduct = productWithTwoSkus();
        ReflectionTestUtils.setField(higherProduct, "id", 20L);
        Product lowerProduct = productWithTwoSkus();
        when(productSKURepository.findProductIdsBySkuIdIn(Set.of(101L, 102L)))
                .thenReturn(List.of(skuProductId(101L, 20L), skuProductId(102L, 10L)));
        when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(lowerProduct));
        when(productRepository.findByIdWithLock(20L)).thenReturn(Optional.of(higherProduct));
        when(productSKURepository.findAllByProductIdWithLock(10L)).thenReturn(lowerProduct.getSkus());
        when(productSKURepository.findAllByProductIdWithLock(20L)).thenReturn(higherProduct.getSkus());

        productService.decreaseStocks(Map.of(101L, 1, 102L, 1));

        InOrder lockOrder = inOrder(productRepository, productSKURepository);
        lockOrder.verify(productRepository).findByIdWithLock(10L);
        lockOrder.verify(productSKURepository).findAllByProductIdWithLock(10L);
        lockOrder.verify(productRepository).findByIdWithLock(20L);
        lockOrder.verify(productSKURepository).findAllByProductIdWithLock(20L);
        verify(productOutboxService).publishProductSnapshot(lowerProduct);
        verify(productOutboxService).publishProductSnapshot(higherProduct);
    }

    @Test
    void givenActiveProduct_whenSoftDeleted_thenPublishesDeletedProductSnapshot() {
        Product product = productWithTwoSkus();
        when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
        when(productSKURepository.findAllByProductIdWithLock(10L)).thenReturn(product.getSkus());

        productService.deleteProduct(10L);

        assertEquals(ProductStatus.DELETED, product.getStatus());
        verify(productOutboxService).publishProductSnapshot(product);
    }

    private Product productWithTwoSkus() {
        Product product = Product.builder()
                .name("사료")
                .status(ProductStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(product, "id", 10L);

        ProductSKU firstSku = ProductSKU.builder()
                .product(product)
                .skuCode("FOOD-S")
                .price(10_000L)
                .stockQuantity(2)
                .build();
        ProductSKU secondSku = ProductSKU.builder()
                .product(product)
                .skuCode("FOOD-L")
                .price(20_000L)
                .stockQuantity(3)
                .build();
        ReflectionTestUtils.setField(firstSku, "id", 101L);
        ReflectionTestUtils.setField(secondSku, "id", 102L);
        product.getSkus().add(firstSku);
        product.getSkus().add(secondSku);
        return product;
    }

    private ProductSKURepository.SkuProductId skuProductId(Long skuId, Long productId) {
        return new ProductSKURepository.SkuProductId() {
            @Override
            public Long getSkuId() {
                return skuId;
            }

            @Override
            public Long getProductId() {
                return productId;
            }
        };
    }
}
