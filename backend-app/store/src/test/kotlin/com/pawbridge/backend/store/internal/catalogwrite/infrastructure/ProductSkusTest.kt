package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.SKUValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.SkuCreateDto
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.util.function.Function

class ProductSkusTest {
    private val skus = mockk<ProductSKURepository>()
    private val links = mockk<SKUValueRepository>()
    private val options = mockk<OptionValueRepository>()
    private val subject = ProductSkus(skus, links, options)

    @Test
    fun `SKU 요청이 없을 때__생성하면__원래의 빈 결과를 유지한다`() {
        // given
        val product = Product.builder().build()

        // when
        val fromNull = subject.createSkus(product, null)
        val fromEmpty = subject.createSkus(Product.builder().build(), listOf())

        // then
        assertThat(fromNull).isEmpty()
        assertThat(fromEmpty).isEmpty()
        confirmVerified(skus, links, options)
    }

    @Test
    fun `옵션 id 순서가 섞여 있을 때__생성하면__중복 없는 id를 먼저 잠그고 원래의 연결을 붙인다`() {
        // given
        val product = Product.builder().name("food").status(ProductStatus.ACTIVE).build()
        val group = OptionGroup.builder().name("size").build()
        val first = OptionValue.builder().optionGroup(group).name("small").build()
        val second = OptionValue.builder().optionGroup(group).name("large").build()
        ReflectionTestUtils.setField(first, "id", 1L)
        ReflectionTestUtils.setField(second, "id", 2L)
        val dto = ObjectMapper().readValue(
            "{\"skuCode\":\"FOOD-L\",\"price\":100,\"stockQuantity\":3,\"optionValueIds\":[2,1]}",
            SkuCreateDto::class.java,
        )

        every { options.findAllByIdWithLock(any()) } returns listOf(first, second)
        every { skus.save(any()) } answers { firstArg<ProductSKU>() }
        every { links.save(any()) } answers { firstArg<SKUValue>() }

        // when
        val created = subject.createSkus(product, listOf(dto))

        // then
        assertThat(created).hasSize(1)
        assertThat(product.skus).containsExactlyElementsOf(created)
        assertThat(created.first().skuCode).isEqualTo("FOOD-L")
        assertThat(created.first().price).isEqualTo(100L)
        assertThat(created.first().stockQuantity).isEqualTo(3)
        assertThat(created.first().skuValues)
            .extracting(Function<SKUValue, OptionValue?> { it.optionValue })
            .containsExactly(second, first)
        verifyOrder {
            options.findAllByIdWithLock(listOf(1L, 2L))
            skus.save(created.first())
            links.save(any())
            links.save(any())
        }
        verify(exactly = 2) { links.save(any()) }
    }

    @Test
    fun `없는 옵션 값일 때__생성하면__SKU나 연결을 저장하기 전에 거부한다`() {
        // given
        val dto = ObjectMapper().readValue(
            "{\"skuCode\":\"FOOD-L\",\"price\":100,\"stockQuantity\":3,\"optionValueIds\":[9]}",
            SkuCreateDto::class.java,
        )

        every { options.findAllByIdWithLock(any()) } returns listOf()

        // when
        val thrown = assertThatThrownBy { subject.createSkus(Product.builder().build(), listOf(dto)) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 1) { options.findAllByIdWithLock(listOf(9L)) }
        confirmVerified(skus, links)
    }
}
