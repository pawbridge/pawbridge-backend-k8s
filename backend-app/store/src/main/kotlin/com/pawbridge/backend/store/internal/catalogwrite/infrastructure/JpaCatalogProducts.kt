package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CartItemRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.SKUValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductUpdateRequest
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Original ProductService create/update/delete on the existing Store writer. */
class JpaCatalogProducts internal constructor(
    private val products: ProductRepository,
    private val categories: CategoryRepository,
    private val skus: ProductSKURepository,
    private val cartItems: CartItemRepository,
    private val skuCreator: ProductSkus,
    private val snapshots: ProductSnapshots,
    private val writes: TransactionOperations,
) {

    constructor(
        sharedWriter: EntityManager,
        writes: TransactionOperations,
        search: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(JpaRepositoryFactory(sharedWriter), writes, search, terms)

    private constructor(
        repositories: JpaRepositoryFactory,
        writes: TransactionOperations,
        search: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(repositories, repositories.getRepository(ProductSKURepository::class.java), writes, search, terms)

    // The SKU repository is created once and shared by the product code and the SKU creator.
    private constructor(
        repositories: JpaRepositoryFactory,
        skus: ProductSKURepository,
        writes: TransactionOperations,
        search: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(
        repositories.getRepository(ProductRepository::class.java),
        repositories.getRepository(CategoryRepository::class.java),
        skus,
        repositories.getRepository(CartItemRepository::class.java),
        ProductSkus(
            skus,
            repositories.getRepository(SKUValueRepository::class.java),
            repositories.getRepository(OptionValueRepository::class.java),
        ),
        ProductSnapshots(search, terms),
        writes,
    )

    fun createProduct(request: ProductCreateRequest): ProductResponse =
        writes.execute {
            requireWriter()
            var product = Product.builder()
                .name(request.name)
                .description(request.description)
                .imageUrl(request.imageUrl)
                .status(ProductStatus.ACTIVE)
                .build()
            val categoryId = request.categoryId
            if (categoryId != null) {
                product.assignCategory(
                    categories.findById(categoryId)
                        .orElseThrow { IllegalArgumentException("Category not found: $categoryId") },
                )
            }
            product = products.save(product)
            val saved = skuCreator.createSkus(product, request.skus)
            val response = ProductResponse.from(product)
            if (!saved.isEmpty()) {
                snapshots.publishProductSnapshot(product)
            }
            response
        }!!

    fun updateProduct(productId: Long?, request: ProductUpdateRequest): ProductResponse =
        writes.execute {
            requireWriter()
            val product = products.findByIdWithLock(productId)
                .orElseThrow { IllegalArgumentException("Product not found: $productId") }
            skus.findAllByProductIdWithLock(productId)
            val name = request.name
            if (name != null) {
                product.updateName(name)
            }
            val description = request.description
            if (description != null) {
                product.updateDescription(description)
            }
            val imageUrl = request.imageUrl
            if (imageUrl != null) {
                product.updateImageUrl(imageUrl)
            }
            // Preserve the original status assignment; the known DELETED bypass is deferred.
            val status = request.status
            if (status != null) {
                product.updateStatus(status)
            }
            val categoryId = request.categoryId
            if (categoryId != null) {
                product.assignCategory(
                    categories.findById(categoryId)
                        .orElseThrow { IllegalArgumentException("Category not found: $categoryId") },
                )
            }
            val requestedSkus = request.skus
            if (requestedSkus != null && requestedSkus.isNotEmpty()) {
                for (requested in requestedSkus) {
                    val requestedId = requested.id
                    if (requestedId == null) {
                        continue
                    }
                    product.skus!!.stream()
                        .filter { sku -> sku.id!! == requestedId }
                        .findFirst()
                        .ifPresent { sku ->
                            val price = requested.price
                            if (price != null) {
                                sku.updatePrice(price)
                            }
                            val stockQuantity = requested.stockQuantity
                            if (stockQuantity != null) {
                                sku.updateStock(stockQuantity)
                            }
                        }
                }
            }
            if (!product.skus!!.isEmpty()) {
                snapshots.publishProductSnapshot(product)
            }
            // Same explicit no-op as existing stock writes: modular GET has no Redis cache consumer.
            ProductResponse.from(product)
        }!!

    fun deleteProduct(productId: Long?) {
        writes.executeWithoutResult {
            requireWriter()
            val product = products.findByIdWithLock(productId)
                .orElseThrow { IllegalArgumentException("Product not found: $productId") }
            skus.findAllByProductIdWithLock(productId)
            if (product.status == ProductStatus.DELETED) {
                throw IllegalStateException("이미 삭제된 상품입니다. 상품 ID: $productId")
            }
            val ids = product.skus!!.stream().map { sku: ProductSKU -> sku.id }.toList()
            if (!ids.isEmpty() && cartItems.existsByProductSkuIdIn(ids)) {
                throw IllegalStateException("장바구니에 담긴 상품은 삭제할 수 없습니다. 상품 ID: $productId")
            }
            product.updateStatus(ProductStatus.DELETED)
            snapshots.publishProductSnapshot(product)
        }
    }

    internal companion object {
        internal fun requireWriter() {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ) {
                throw IllegalStateException("Catalog changes require the existing active Store writer transaction")
            }
        }
    }
}
