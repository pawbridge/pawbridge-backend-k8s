package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.anyNonNull
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CartItemRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.SKUValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductUpdateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.SkuDetailDto
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.function.Consumer

class JpaCatalogProductsTest {
    private val products: ProductRepository = mock(ProductRepository::class.java)
    private val categories: CategoryRepository = mock(CategoryRepository::class.java)
    private val skus: ProductSKURepository = mock(ProductSKURepository::class.java)
    private val carts: CartItemRepository = mock(CartItemRepository::class.java)
    private val links: SKUValueRepository = mock(SKUValueRepository::class.java)
    private val options: OptionValueRepository = mock(OptionValueRepository::class.java)
    private val snapshots: ProductSnapshots = mock(ProductSnapshots::class.java)
    private val manager = Writer()
    private val subject = JpaCatalogProducts(
        products,
        categories,
        skus,
        carts,
        ProductSkus(skus, links, options),
        snapshots,
        TransactionTemplate(manager),
    )
    private val mapper = ObjectMapper()

    @AfterEach
    fun resourcesReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty()
    }

    @Test
    fun givenCreateWithSku__whenSaved__thenReturnOriginalFieldsAndPublishWithinWriter() {
        `when`(products.save(any(Product::class.java))).thenAnswer { call ->
            val p = call.getArgument<Product>(0)
            ReflectionTestUtils.setField(p, "id", 10L)
            p
        }
        `when`(skus.save(any(ProductSKU::class.java))).thenAnswer { call ->
            val sku = call.getArgument<ProductSKU>(0)
            ReflectionTestUtils.setField(sku, "id", 101L)
            sku
        }
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            null
        }.`when`(snapshots).publishProductSnapshot(anyNonNull<Product>())

        val response: ProductResponse = subject.createProduct(
            mapper.readValue(
                """
                {"name":"food","description":"description","imageUrl":"https://assets.invalid/p.png",
                 "skus":[{"skuCode":"FOOD-S","price":100,"stockQuantity":3}]}
                """.trimIndent() + "\n",
                ProductCreateRequest::class.java,
            ),
        )

        assertThat(response.id).isEqualTo(10L)
        assertThat(response.status).isEqualTo("ACTIVE")
        assertThat(response.name).isEqualTo("food")
        assertThat(response.description).isEqualTo("description")
        assertThat(response.imageUrl).isEqualTo("https://assets.invalid/p.png")
        assertThat(response.skus).singleElement().satisfies(
            Consumer<SkuDetailDto> { sku ->
                assertThat(sku.skuId).isEqualTo(101L)
                assertThat(sku.skuCode).isEqualTo("FOOD-S")
                assertThat(sku.price).isEqualTo(100L)
                assertThat(sku.stockQuantity).isEqualTo(3)
                assertThat(sku.options).isEmpty()
            },
        )
        verify(snapshots).publishProductSnapshot(anyNonNull<Product>())
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenCreateWithoutSkus__whenSaved__thenKeepOriginalNoSnapshotBranch() {
        `when`(products.save(any(Product::class.java))).thenAnswer { call -> call.getArgument<Product>(0) }

        val response = subject.createProduct(mapper.readValue("{\"name\":\"no sku\"}", ProductCreateRequest::class.java))

        assertThat(response.skus).isEmpty()
        verifyNoInteractions(snapshots, skus, options, links)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenProductAndSkuPatch__whenUpdated__thenLockAndPublishWholeProduct() {
        val p = product()
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(p))

        val response = subject.updateProduct(
            10L,
            mapper.readValue(
                """
                {"name":"new food","description":"new description","imageUrl":"new.png","status":"HIDDEN",
                 "skus":[{"id":101,"price":300,"stockQuantity":8}]}
                """.trimIndent() + "\n",
                ProductUpdateRequest::class.java,
            ),
        )

        val order = inOrder(products, skus, snapshots)
        order.verify(products).findByIdWithLock(10L)
        order.verify(skus).findAllByProductIdWithLock(10L)
        order.verify(snapshots).publishProductSnapshot(p)
        assertThat(response.name).isEqualTo("new food")
        assertThat(response.description).isEqualTo("new description")
        assertThat(response.imageUrl).isEqualTo("new.png")
        assertThat(response.status).isEqualTo("HIDDEN")
        assertThat(response.skus!!.first().price).isEqualTo(300L)
        assertThat(response.skus!!.first().stockQuantity).isEqualTo(8)
        assertThat(response.skus).hasSize(2)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenSkuReferencedByCart__whenDeleting__thenRefuseBeforeStatusAndSnapshot() {
        val p = product()
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(p))
        `when`(carts.existsByProductSkuIdIn(listOf(101L, 102L))).thenReturn(true)

        assertThatThrownBy { subject.deleteProduct(10L) }.isInstanceOf(IllegalStateException::class.java)

        assertThat(p.status).isEqualTo(ProductStatus.ACTIVE)
        verifyNoInteractions(snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
        assertThat(manager.commits).isZero()
    }

    @Test
    fun givenAlreadyDeletedProduct__whenDeleting__thenRefuseBeforeCartAndSnapshot() {
        val p = product()
        p.updateStatus(ProductStatus.DELETED)
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(p))

        assertThatThrownBy { subject.deleteProduct(10L) }.isInstanceOf(IllegalStateException::class.java)

        verifyNoInteractions(carts, snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun givenUnreferencedProduct__whenDeleting__thenSoftDeleteAndPublishWithinOneWriter() {
        val p = product()
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(p))

        subject.deleteProduct(10L)

        assertThat(p.status).isEqualTo(ProductStatus.DELETED)
        verify(carts).existsByProductSkuIdIn(listOf(101L, 102L))
        verify(snapshots).publishProductSnapshot(p)
        verify(products, never()).delete(any(Product::class.java))
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenSnapshotFailure__whenUpdating__thenPropagateAndRequestWriterRollback() {
        val p = product()
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(p))
        val failure = IllegalStateException("synthetic snapshot failure")
        doThrow(failure).`when`(snapshots).publishProductSnapshot(p)

        assertThatThrownBy { subject.updateProduct(10L, ProductUpdateRequest()) }.isSameAs(failure)

        assertThat(manager.rollbacks).isEqualTo(1)
        assertThat(manager.commits).isZero()
        // This unit proves rollback dispatch, not actual database restoration.
    }

    @Test
    fun givenMissingCategory__whenCreating__thenRefuseBeforeProductSave() {
        `when`(categories.findById(99L)).thenReturn(Optional.empty())
        val request = mapper.readValue("{\"name\":\"food\",\"categoryId\":99}", ProductCreateRequest::class.java)

        assertThatThrownBy { subject.createProduct(request) }.isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(products, skus, snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    private class Writer : AbstractPlatformTransactionManager() {
        var commits = 0
        var rollbacks = 0

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            commits++
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks++
        }
    }

    companion object {
        private fun product(): Product {
            val p = Product.builder().name("food").status(ProductStatus.ACTIVE).build()
            ReflectionTestUtils.setField(p, "id", 10L)
            for (id in listOf(101L, 102L)) {
                val sku = ProductSKU.builder().product(p).skuCode("FOOD-$id").price(100L).stockQuantity(3).build()
                ReflectionTestUtils.setField(sku, "id", id)
                p.skus!!.add(sku)
            }
            return p
        }
    }
}
