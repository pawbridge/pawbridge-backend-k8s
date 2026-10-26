package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.function.Consumer

class JpaCatalogProductsTest {
    private val products = mockk<ProductRepository>()
    private val categories = mockk<CategoryRepository>()
    private val skus = mockk<ProductSKURepository>()
    private val carts = mockk<CartItemRepository>()
    private val links = mockk<SKUValueRepository>()
    private val options = mockk<OptionValueRepository>()
    private val snapshots = mockk<ProductSnapshots>()
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
    fun `SKU가 있는 생성 요청일 때__저장하면__원래 필드를 돌려주고 쓰기 트랜잭션 안에서 발행한다`() {
        // given
        val request = mapper.readValue(
            """
            {"name":"food","description":"description","imageUrl":"https://assets.invalid/p.png",
             "skus":[{"skuCode":"FOOD-S","price":100,"stockQuantity":3}]}
            """.trimIndent() + "\n",
            ProductCreateRequest::class.java,
        )

        every { products.save(any()) } answers {
            val p = firstArg<Product>()
            ReflectionTestUtils.setField(p, "id", 10L)
            p
        }
        every { skus.save(any()) } answers {
            val sku = firstArg<ProductSKU>()
            ReflectionTestUtils.setField(sku, "id", 101L)
            sku
        }
        every { snapshots.publishProductSnapshot(any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
        }

        // when
        val response: ProductResponse = subject.createProduct(request)

        // then
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
        verify(exactly = 1) { snapshots.publishProductSnapshot(any<Product>()) }
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `SKU가 없는 생성 요청일 때__저장하면__원래의 스냅샷 없음 분기를 유지한다`() {
        // given
        val request = mapper.readValue("{\"name\":\"no sku\"}", ProductCreateRequest::class.java)

        every { products.save(any()) } answers { firstArg<Product>() }

        // when
        val response = subject.createProduct(request)

        // then
        assertThat(response.skus).isEmpty()
        confirmVerified(snapshots, skus, options, links)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `상품과 SKU 패치가 있을 때__수정하면__전체 상품을 잠그고 발행한다`() {
        // given
        val p = product()
        val request = mapper.readValue(
            """
            {"name":"new food","description":"new description","imageUrl":"new.png","status":"HIDDEN",
             "skus":[{"id":101,"price":300,"stockQuantity":8}]}
            """.trimIndent() + "\n",
            ProductUpdateRequest::class.java,
        )

        every { products.findByIdWithLock(any()) } returns Optional.of(p)
        every { skus.findAllByProductIdWithLock(any()) } returns listOf()
        justRun { snapshots.publishProductSnapshot(any()) }

        // when
        val response = subject.updateProduct(10L, request)

        // then
        verifyOrder {
            products.findByIdWithLock(10L)
            skus.findAllByProductIdWithLock(10L)
            snapshots.publishProductSnapshot(p)
        }
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
    fun `SKU가 장바구니에 담겨 있을 때__삭제하면__상태 변경과 스냅샷 전에 거부한다`() {
        // given
        val p = product()

        every { products.findByIdWithLock(any()) } returns Optional.of(p)
        every { skus.findAllByProductIdWithLock(any()) } returns listOf()
        every { carts.existsByProductSkuIdIn(any()) } returns true

        // when
        val thrown = assertThatThrownBy { subject.deleteProduct(10L) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        assertThat(p.status).isEqualTo(ProductStatus.ACTIVE)
        verify(exactly = 1) { carts.existsByProductSkuIdIn(listOf(101L, 102L)) }
        confirmVerified(snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
        assertThat(manager.commits).isZero()
    }

    @Test
    fun `이미 삭제된 상품일 때__삭제하면__장바구니 확인과 스냅샷 전에 거부한다`() {
        // given
        val p = product()
        p.updateStatus(ProductStatus.DELETED)

        every { products.findByIdWithLock(any()) } returns Optional.of(p)
        every { skus.findAllByProductIdWithLock(any()) } returns listOf()

        // when
        val thrown = assertThatThrownBy { subject.deleteProduct(10L) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        confirmVerified(carts, snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun `참조되지 않는 상품일 때__삭제하면__하나의 쓰기 트랜잭션에서 소프트 삭제하고 발행한다`() {
        // given
        val p = product()

        every { products.findByIdWithLock(any()) } returns Optional.of(p)
        every { skus.findAllByProductIdWithLock(any()) } returns listOf()
        every { carts.existsByProductSkuIdIn(any()) } returns false
        justRun { snapshots.publishProductSnapshot(any()) }

        // when
        subject.deleteProduct(10L)

        // then
        assertThat(p.status).isEqualTo(ProductStatus.DELETED)
        verify(exactly = 1) { carts.existsByProductSkuIdIn(listOf(101L, 102L)) }
        verify(exactly = 1) { snapshots.publishProductSnapshot(p) }
        verify(exactly = 0) { products.delete(any()) }
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `스냅샷 발행이 실패할 때__수정하면__전파하고 쓰기 트랜잭션 롤백을 요청한다`() {
        // given
        val p = product()
        val failure = IllegalStateException("synthetic snapshot failure")

        every { products.findByIdWithLock(any()) } returns Optional.of(p)
        every { skus.findAllByProductIdWithLock(any()) } returns listOf()
        every { snapshots.publishProductSnapshot(any()) } throws failure

        // when
        val thrown = assertThatThrownBy { subject.updateProduct(10L, ProductUpdateRequest()) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { snapshots.publishProductSnapshot(p) }
        assertThat(manager.rollbacks).isEqualTo(1)
        assertThat(manager.commits).isZero()
        // This unit proves rollback dispatch, not actual database restoration.
    }

    @Test
    fun `없는 카테고리일 때__생성하면__상품 저장 전에 거부한다`() {
        // given
        val request = mapper.readValue("{\"name\":\"food\",\"categoryId\":99}", ProductCreateRequest::class.java)

        every { categories.findById(any()) } returns Optional.empty()

        // when
        val thrown = assertThatThrownBy { subject.createProduct(request) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 1) { categories.findById(99L) }
        confirmVerified(products, skus, snapshots)
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
