package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.*;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original ProductService create/update/delete on the existing Store writer. */
public final class JpaCatalogProducts {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductSKURepository skus;
    private final CartItemRepository cartItems;
    private final ProductSkus skuCreator;
    private final ProductSnapshots snapshots;
    private final TransactionOperations writes;

    public JpaCatalogProducts(EntityManager sharedWriter,TransactionOperations writes,
            NamedParameterJdbcOperations search,KoreanSearchTerms terms) {
        var repositories=new JpaRepositoryFactory(sharedWriter);
        this.products=repositories.getRepository(ProductRepository.class);
        this.categories=repositories.getRepository(CategoryRepository.class);
        this.skus=repositories.getRepository(ProductSKURepository.class);
        this.cartItems=repositories.getRepository(CartItemRepository.class);
        this.skuCreator=new ProductSkus(skus,repositories.getRepository(SKUValueRepository.class),
                repositories.getRepository(OptionValueRepository.class));
        this.snapshots=new ProductSnapshots(search,terms);
        this.writes=writes;
    }

    JpaCatalogProducts(ProductRepository products,CategoryRepository categories,ProductSKURepository skus,
            CartItemRepository cartItems,ProductSkus skuCreator,ProductSnapshots snapshots,TransactionOperations writes) {
        this.products=products;this.categories=categories;this.skus=skus;this.cartItems=cartItems;
        this.skuCreator=skuCreator;this.snapshots=snapshots;this.writes=writes;
    }

    public ProductResponse createProduct(ProductCreateRequest request) {
        return writes.execute(tx->{
            requireWriter();
            Product product=Product.builder().name(request.getName()).description(request.getDescription())
                    .imageUrl(request.getImageUrl()).status(ProductStatus.ACTIVE).build();
            if(request.getCategoryId()!=null) product.assignCategory(categories.findById(request.getCategoryId())
                    .orElseThrow(()->new IllegalArgumentException("Category not found: "+request.getCategoryId())));
            product=products.save(product);
            var saved=skuCreator.createSkus(product,request.getSkus());
            ProductResponse response=ProductResponse.from(product);
            if(!saved.isEmpty()) snapshots.publishProductSnapshot(product);
            return response;
        });
    }

    public ProductResponse updateProduct(Long productId,ProductUpdateRequest request) {
        return writes.execute(tx->{
            requireWriter();
            Product product=products.findByIdWithLock(productId)
                    .orElseThrow(()->new IllegalArgumentException("Product not found: "+productId));
            skus.findAllByProductIdWithLock(productId);
            if(request.getName()!=null) product.updateName(request.getName());
            if(request.getDescription()!=null) product.updateDescription(request.getDescription());
            if(request.getImageUrl()!=null) product.updateImageUrl(request.getImageUrl());
            // Preserve the original status assignment; the known DELETED bypass is deferred.
            if(request.getStatus()!=null) product.updateStatus(request.getStatus());
            if(request.getCategoryId()!=null) product.assignCategory(categories.findById(request.getCategoryId())
                    .orElseThrow(()->new IllegalArgumentException("Category not found: "+request.getCategoryId())));
            if(request.getSkus()!=null&&!request.getSkus().isEmpty()) {
                for(SkuUpdateDto requested:request.getSkus()) {
                    if(requested.getId()==null) continue;
                    product.getSkus().stream().filter(sku->sku.getId().equals(requested.getId())).findFirst().ifPresent(sku->{
                        if(requested.getPrice()!=null) sku.updatePrice(requested.getPrice());
                        if(requested.getStockQuantity()!=null) sku.updateStock(requested.getStockQuantity());
                    });
                }
            }
            if(!product.getSkus().isEmpty()) snapshots.publishProductSnapshot(product);
            // Same explicit no-op as existing stock writes: modular GET has no Redis cache consumer.
            return ProductResponse.from(product);
        });
    }

    public void deleteProduct(Long productId) {
        writes.executeWithoutResult(tx->{
            requireWriter();
            Product product=products.findByIdWithLock(productId)
                    .orElseThrow(()->new IllegalArgumentException("Product not found: "+productId));
            skus.findAllByProductIdWithLock(productId);
            if(product.getStatus()==ProductStatus.DELETED)
                throw new IllegalStateException("이미 삭제된 상품입니다. 상품 ID: "+productId);
            var ids=product.getSkus().stream().map(ProductSKU::getId).toList();
            if(!ids.isEmpty()&&cartItems.existsByProductSkuIdIn(ids))
                throw new IllegalStateException("장바구니에 담긴 상품은 삭제할 수 없습니다. 상품 ID: "+productId);
            product.updateStatus(ProductStatus.DELETED);
            snapshots.publishProductSnapshot(product);
        });
    }

    static void requireWriter() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Catalog changes require the existing active Store writer transaction");
    }
}
