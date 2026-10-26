package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionGroupRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueResponse
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
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
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Function

class JpaCatalogOptionsTest {
    private val groups = mockk<OptionGroupRepository>()
    private val values = mockk<OptionValueRepository>()
    private val products = mockk<ProductRepository>()
    private val skus = mockk<ProductSKURepository>()
    private val snapshots = mockk<ProductSnapshots>()
    private val manager = Writer()
    private val subject = JpaCatalogOptions(groups, values, products, skus, snapshots, TransactionTemplate(manager))
    private val mapper = ObjectMapper()

    @AfterEach
    fun resourcesReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty()
    }

    @Test
    fun `새 그룹과 값이 있을 때__생성하면__원래의 그룹과 값 모양을 돌려준다`() {
        // given
        val request = mapper.readValue(
            "{\"name\":\"size\",\"values\":[\"small\",\"large\"]}",
            OptionGroupRequest::class.java,
        )
        val sequence = AtomicLong(10L)

        every { groups.existsByName(any()) } returns false
        every { groups.save(any()) } answers {
            val group = firstArg<OptionGroup>()
            ReflectionTestUtils.setField(group, "id", 1L)
            group
        }
        every { values.save(any()) } answers {
            val value = firstArg<OptionValue>()
            ReflectionTestUtils.setField(value, "id", sequence.incrementAndGet())
            value
        }

        // when
        val result = subject.createOptionGroup(request)

        // then
        assertThat(result.id).isEqualTo(1L)
        assertThat(result.name).isEqualTo("size")
        assertThat(result.values)
            .extracting(Function<OptionValueResponse, String?> { it.name })
            .containsExactly("small", "large")
        assertThat(result.values).allSatisfy(
            Consumer<OptionValueResponse> { value ->
                assertThat(value.groupId).isEqualTo(1L)
                assertThat(value.groupName).isEqualTo("size")
            },
        )
        confirmVerified(products, skus, snapshots)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `그룹 이름이 중복일 때__생성하면__저장 전에 거부한다`() {
        // given
        val request = mapper.readValue("{\"name\":\"size\"}", OptionGroupRequest::class.java)

        every { groups.existsByName(any()) } returns true

        // when
        val thrown = assertThatThrownBy { subject.createOptionGroup(request) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { groups.existsByName("size") }
        verify(exactly = 0) { groups.save(any()) }
        confirmVerified(values, snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun `사용 중인 그룹일 때__이름을 바꾸면__영향받는 상품을 순서대로 잠그고 각각 발행한다`() {
        // given
        val group = group()
        val first = product(10L)
        val second = product(20L)
        val request = mapper.readValue("{\"name\":\"new size\"}", OptionGroupRequest::class.java)

        every { groups.findByIdWithLock(any()) } returns Optional.of(group)
        every { values.findAllByOptionGroupIdWithLock(any()) } returns listOf()
        every { skus.findProductIdsByOptionGroupId(any()) } returns listOf(20L, 10L)
        every { products.findByIdWithLock(10L) } returns Optional.of(first)
        every { products.findByIdWithLock(20L) } returns Optional.of(second)
        every { skus.findAllByProductIdWithLock(10L) } returns first.skus!!
        every { skus.findAllByProductIdWithLock(20L) } returns second.skus!!
        justRun { snapshots.publishProductSnapshot(any()) }

        // when
        val result = subject.updateOptionGroup(1L, request)

        // then
        assertThat(result.name).isEqualTo("new size")
        verifyOrder {
            groups.findByIdWithLock(1L)
            values.findAllByOptionGroupIdWithLock(1L)
            skus.findProductIdsByOptionGroupId(1L)
            products.findByIdWithLock(10L)
            skus.findAllByProductIdWithLock(10L)
            snapshots.publishProductSnapshot(first)
            products.findByIdWithLock(20L)
            skus.findAllByProductIdWithLock(20L)
            snapshots.publishProductSnapshot(second)
        }
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `사용 중인 값일 때__이름을 바꾸면__영향받는 상품을 다시 발행한다`() {
        // given
        val value = value()
        val product = product(10L)
        val request = mapper.readValue("{\"name\":\"new small\"}", OptionValueRequest::class.java)

        every { values.findByIdWithLock(any()) } returns Optional.of(value)
        every { skus.findProductIdsByOptionValueId(any()) } returns listOf(10L)
        every { products.findByIdWithLock(any()) } returns Optional.of(product)
        every { skus.findAllByProductIdWithLock(any()) } returns product.skus!!
        justRun { snapshots.publishProductSnapshot(any()) }

        // when
        val result = subject.updateOptionValue(2L, request)

        // then
        assertThat(result.name).isEqualTo("new small")
        assertThat(result.groupName).isEqualTo("size")
        verify(exactly = 1) { values.findByIdWithLock(2L) }
        verify(exactly = 1) { skus.findProductIdsByOptionValueId(2L) }
        verify(exactly = 1) { products.findByIdWithLock(10L) }
        verify(exactly = 1) { skus.findAllByProductIdWithLock(10L) }
        verify(exactly = 1) { snapshots.publishProductSnapshot(product) }
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun `참조되는 값을 가진 그룹일 때__삭제하면__물리 삭제를 거부한다`() {
        // given
        val value = value()
        val group = value.optionGroup!!
        group.optionValues.add(value)

        every { groups.findById(any()) } returns Optional.of(group)
        every { values.isOptionValueInUse(any()) } returns true

        // when
        val thrown = assertThatThrownBy { subject.deleteOptionGroup(1L) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { groups.findById(1L) }
        verify(exactly = 1) { values.isOptionValueInUse(2L) }
        verify(exactly = 0) { groups.delete(any()) }
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun `참조되는 값일 때__삭제하면__물리 삭제를 거부한다`() {
        // given
        every { values.findById(any()) } returns Optional.of(value())
        every { values.isOptionValueInUse(any()) } returns true

        // when
        val thrown = assertThatThrownBy { subject.deleteOptionValue(2L) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { values.findById(2L) }
        verify(exactly = 1) { values.isOptionValueInUse(2L) }
        verify(exactly = 0) { values.delete(any()) }
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun `참조되지 않는 그룹과 값일 때__삭제하면__원래의 물리 삭제를 유지한다`() {
        // given
        val group = group()
        val value = value()

        every { groups.findById(any()) } returns Optional.of(group)
        every { values.findById(any()) } returns Optional.of(value)
        every { values.isOptionValueInUse(any()) } returns false
        justRun { groups.delete(any()) }
        justRun { values.delete(any()) }

        // when
        subject.deleteOptionGroup(1L)
        subject.deleteOptionValue(2L)

        // then
        verify(exactly = 1) { groups.findById(1L) }
        verify(exactly = 1) { values.findById(2L) }
        verify(exactly = 1) { groups.delete(group) }
        verify(exactly = 1) { values.delete(value) }
        confirmVerified(snapshots)
        assertThat(manager.commits).isEqualTo(2)
    }

    @Test
    fun `그룹이 있을 때__값을 추가하면__그룹에 붙이고 그룹 신원을 돌려준다`() {
        // given
        val group = group()
        val request = mapper.readValue("{\"name\":\"small\"}", OptionValueRequest::class.java)

        every { groups.findById(any()) } returns Optional.of(group)
        every { values.save(any()) } answers {
            val value = firstArg<OptionValue>()
            ReflectionTestUtils.setField(value, "id", 2L)
            value
        }

        // when
        val result = subject.addOptionValue(1L, request)

        // then
        assertThat(result.id).isEqualTo(2L)
        assertThat(result.name).isEqualTo("small")
        assertThat(result.groupId).isEqualTo(1L)
        assertThat(result.groupName).isEqualTo("size")
        assertThat(group.optionValues).hasSize(1)
        verify(exactly = 1) { groups.findById(1L) }
    }

    @Test
    fun `스냅샷 발행이 실패할 때__이름을 바꾸면__쓰기 트랜잭션 롤백으로 전파한다`() {
        // given
        val value = value()
        val product = product(10L)
        val failure = IllegalStateException("synthetic snapshot failure")
        val request = mapper.readValue("{\"name\":\"new small\"}", OptionValueRequest::class.java)

        every { values.findByIdWithLock(any()) } returns Optional.of(value)
        every { skus.findProductIdsByOptionValueId(any()) } returns listOf(10L)
        every { products.findByIdWithLock(any()) } returns Optional.of(product)
        every { skus.findAllByProductIdWithLock(any()) } returns product.skus!!
        every { snapshots.publishProductSnapshot(any()) } throws failure

        // when
        val thrown = assertThatThrownBy { subject.updateOptionValue(2L, request) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { snapshots.publishProductSnapshot(product) }
        assertThat(manager.rollbacks).isEqualTo(1)
        assertThat(manager.commits).isZero()
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
        private fun group(): OptionGroup {
            val group = OptionGroup.builder().name("size").build()
            ReflectionTestUtils.setField(group, "id", 1L)
            return group
        }

        private fun value(): OptionValue {
            val value = OptionValue.builder().optionGroup(group()).name("small").build()
            ReflectionTestUtils.setField(value, "id", 2L)
            return value
        }

        private fun product(id: Long): Product {
            val p = Product.builder().name("food").status(ProductStatus.ACTIVE).build()
            ReflectionTestUtils.setField(p, "id", id)
            val sku = ProductSKU.builder().product(p).skuCode("FOOD-$id").price(100L).stockQuantity(3).build()
            ReflectionTestUtils.setField(sku, "id", id * 10)
            p.skus!!.add(sku)
            return p
        }
    }
}
