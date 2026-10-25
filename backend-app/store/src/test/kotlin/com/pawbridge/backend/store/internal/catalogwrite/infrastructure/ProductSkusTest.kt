package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.SKUValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.SkuCreateDto
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.util.ReflectionTestUtils
import java.util.function.Function

class ProductSkusTest {
    private val skus: ProductSKURepository = mock(ProductSKURepository::class.java)
    private val links: SKUValueRepository = mock(SKUValueRepository::class.java)
    private val options: OptionValueRepository = mock(OptionValueRepository::class.java)
    private val subject = ProductSkus(skus, links, options)

    @Test
    fun givenNoSkuRequests__whenCreating__thenKeepOriginalEmptyResult() {
        assertThat(subject.createSkus(Product.builder().build(), null)).isEmpty()
        assertThat(subject.createSkus(Product.builder().build(), listOf())).isEmpty()
        verifyNoInteractions(skus, links, options)
    }

    @Test
    fun givenUnorderedOptionIds__whenCreating__thenLockDistinctIdsFirstAndAttachOriginalLinks() {
        val product = Product.builder().name("food").status(ProductStatus.ACTIVE).build()
        val group = OptionGroup.builder().name("size").build()
        val first = OptionValue.builder().optionGroup(group).name("small").build()
        val second = OptionValue.builder().optionGroup(group).name("large").build()
        ReflectionTestUtils.setField(first, "id", 1L)
        ReflectionTestUtils.setField(second, "id", 2L)
        `when`(options.findAllByIdWithLock(listOf(1L, 2L))).thenReturn(listOf(first, second))
        val dto = ObjectMapper().readValue(
            "{\"skuCode\":\"FOOD-L\",\"price\":100,\"stockQuantity\":3,\"optionValueIds\":[2,1]}",
            SkuCreateDto::class.java,
        )

        val created = subject.createSkus(product, listOf(dto))

        assertThat(created).hasSize(1)
        assertThat(product.skus).containsExactlyElementsOf(created)
        assertThat(created.first().skuCode).isEqualTo("FOOD-L")
        assertThat(created.first().price).isEqualTo(100L)
        assertThat(created.first().stockQuantity).isEqualTo(3)
        assertThat(created.first().skuValues)
            .extracting(Function<SKUValue, OptionValue?> { it.optionValue })
            .containsExactly(second, first)
        val order = inOrder(options, skus, links)
        order.verify(options).findAllByIdWithLock(listOf(1L, 2L))
        order.verify(skus).save(created.first())
        order.verify(links, times(2)).save(any(SKUValue::class.java))
    }

    @Test
    fun givenMissingOptionValue__whenCreating__thenRefuseBeforeAnySkuOrLinkSave() {
        `when`(options.findAllByIdWithLock(listOf(9L))).thenReturn(listOf())
        val dto = ObjectMapper().readValue(
            "{\"skuCode\":\"FOOD-L\",\"price\":100,\"stockQuantity\":3,\"optionValueIds\":[9]}",
            SkuCreateDto::class.java,
        )

        assertThatThrownBy { subject.createSkus(Product.builder().build(), listOf(dto)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(skus, links)
    }
}
