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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
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
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Function

class JpaCatalogOptionsTest {
    private val groups: OptionGroupRepository = mock(OptionGroupRepository::class.java)
    private val values: OptionValueRepository = mock(OptionValueRepository::class.java)
    private val products: ProductRepository = mock(ProductRepository::class.java)
    private val skus: ProductSKURepository = mock(ProductSKURepository::class.java)
    private val snapshots: ProductSnapshots = mock(ProductSnapshots::class.java)
    private val manager = Writer()
    private val subject = JpaCatalogOptions(groups, values, products, skus, snapshots, TransactionTemplate(manager))
    private val mapper = ObjectMapper()

    @AfterEach
    fun resourcesReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty()
    }

    @Test
    fun givenNewGroupAndValues__whenCreated__thenReturnOriginalGroupAndValueShape() {
        `when`(groups.save(any(OptionGroup::class.java))).thenAnswer { call ->
            val group = call.getArgument<OptionGroup>(0)
            ReflectionTestUtils.setField(group, "id", 1L)
            group
        }
        val sequence = AtomicLong(10L)
        `when`(values.save(any(OptionValue::class.java))).thenAnswer { call ->
            val value = call.getArgument<OptionValue>(0)
            ReflectionTestUtils.setField(value, "id", sequence.incrementAndGet())
            value
        }

        val result = subject.createOptionGroup(
            mapper.readValue("{\"name\":\"size\",\"values\":[\"small\",\"large\"]}", OptionGroupRequest::class.java),
        )

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
        verifyNoInteractions(products, skus, snapshots)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenDuplicateGroupName__whenCreating__thenRejectBeforePersisting() {
        `when`(groups.existsByName("size")).thenReturn(true)
        val request = mapper.readValue("{\"name\":\"size\"}", OptionGroupRequest::class.java)

        assertThatThrownBy { subject.createOptionGroup(request) }.isInstanceOf(IllegalStateException::class.java)

        verify(groups, never()).save(any(OptionGroup::class.java))
        verifyNoInteractions(values, snapshots)
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun givenUsedGroup__whenRenamed__thenLockAffectedProductsInOrderAndPublishEach() {
        val group = group()
        val first = product(10L)
        val second = product(20L)
        `when`(groups.findByIdWithLock(1L)).thenReturn(Optional.of(group))
        `when`(skus.findProductIdsByOptionGroupId(1L)).thenReturn(listOf(20L, 10L))
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(first))
        `when`(products.findByIdWithLock(20L)).thenReturn(Optional.of(second))
        `when`(skus.findAllByProductIdWithLock(10L)).thenReturn(first.skus!!)
        `when`(skus.findAllByProductIdWithLock(20L)).thenReturn(second.skus!!)

        val result = subject.updateOptionGroup(1L, mapper.readValue("{\"name\":\"new size\"}", OptionGroupRequest::class.java))

        assertThat(result.name).isEqualTo("new size")
        val order = inOrder(groups, values, skus, products, snapshots)
        order.verify(groups).findByIdWithLock(1L)
        order.verify(values).findAllByOptionGroupIdWithLock(1L)
        order.verify(skus).findProductIdsByOptionGroupId(1L)
        order.verify(products).findByIdWithLock(10L)
        order.verify(skus).findAllByProductIdWithLock(10L)
        order.verify(snapshots).publishProductSnapshot(first)
        order.verify(products).findByIdWithLock(20L)
        order.verify(skus).findAllByProductIdWithLock(20L)
        order.verify(snapshots).publishProductSnapshot(second)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenUsedValue__whenRenamed__thenRepublishAffectedProduct() {
        val value = value()
        val product = product(10L)
        `when`(values.findByIdWithLock(2L)).thenReturn(Optional.of(value))
        `when`(skus.findProductIdsByOptionValueId(2L)).thenReturn(listOf(10L))
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(product))
        `when`(skus.findAllByProductIdWithLock(10L)).thenReturn(product.skus!!)

        val result = subject.updateOptionValue(2L, mapper.readValue("{\"name\":\"new small\"}", OptionValueRequest::class.java))

        assertThat(result.name).isEqualTo("new small")
        assertThat(result.groupName).isEqualTo("size")
        verify(snapshots).publishProductSnapshot(product)
        assertThat(manager.commits).isEqualTo(1)
    }

    @Test
    fun givenGroupWithReferencedValue__whenDeleting__thenRefusePhysicalDelete() {
        val value = value()
        val group = value.optionGroup!!
        group.optionValues.add(value)
        `when`(groups.findById(1L)).thenReturn(Optional.of(group))
        `when`(values.isOptionValueInUse(2L)).thenReturn(true)

        assertThatThrownBy { subject.deleteOptionGroup(1L) }.isInstanceOf(IllegalStateException::class.java)

        verify(groups, never()).delete(any(OptionGroup::class.java))
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun givenReferencedValue__whenDeleting__thenRefusePhysicalDelete() {
        `when`(values.findById(2L)).thenReturn(Optional.of(value()))
        `when`(values.isOptionValueInUse(2L)).thenReturn(true)

        assertThatThrownBy { subject.deleteOptionValue(2L) }.isInstanceOf(IllegalStateException::class.java)

        verify(values, never()).delete(any(OptionValue::class.java))
        assertThat(manager.rollbacks).isEqualTo(1)
    }

    @Test
    fun givenUnreferencedGroupAndValue__whenDeleted__thenKeepOriginalPhysicalDeletes() {
        val group = group()
        val value = value()
        `when`(groups.findById(1L)).thenReturn(Optional.of(group))
        `when`(values.findById(2L)).thenReturn(Optional.of(value))

        subject.deleteOptionGroup(1L)
        subject.deleteOptionValue(2L)

        verify(groups).delete(group)
        verify(values).delete(value)
        verifyNoInteractions(snapshots)
        assertThat(manager.commits).isEqualTo(2)
    }

    @Test
    fun givenExistingGroup__whenAddingValue__thenAttachAndReturnGroupIdentity() {
        val group = group()
        `when`(groups.findById(1L)).thenReturn(Optional.of(group))
        `when`(values.save(any(OptionValue::class.java))).thenAnswer { call ->
            val value = call.getArgument<OptionValue>(0)
            ReflectionTestUtils.setField(value, "id", 2L)
            value
        }

        val result = subject.addOptionValue(1L, mapper.readValue("{\"name\":\"small\"}", OptionValueRequest::class.java))

        assertThat(result.id).isEqualTo(2L)
        assertThat(result.name).isEqualTo("small")
        assertThat(result.groupId).isEqualTo(1L)
        assertThat(result.groupName).isEqualTo("size")
        assertThat(group.optionValues).hasSize(1)
    }

    @Test
    fun givenSnapshotFailure__whenRenaming__thenPropagateToWriterRollback() {
        val value = value()
        val product = product(10L)
        `when`(values.findByIdWithLock(2L)).thenReturn(Optional.of(value))
        `when`(skus.findProductIdsByOptionValueId(2L)).thenReturn(listOf(10L))
        `when`(products.findByIdWithLock(10L)).thenReturn(Optional.of(product))
        `when`(skus.findAllByProductIdWithLock(10L)).thenReturn(product.skus!!)
        val failure = IllegalStateException("synthetic snapshot failure")
        doThrow(failure).`when`(snapshots).publishProductSnapshot(product)
        val request = mapper.readValue("{\"name\":\"new small\"}", OptionValueRequest::class.java)

        assertThatThrownBy { subject.updateOptionValue(2L, request) }.isSameAs(failure)

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
