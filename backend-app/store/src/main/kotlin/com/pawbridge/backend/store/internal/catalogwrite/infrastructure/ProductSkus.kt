package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.OptionValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.SKUValueRepository
import com.pawbridge.backend.store.internal.catalogwrite.web.SkuCreateDto
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue
import java.util.function.Function
import java.util.stream.Collectors

/** Original SKU creation; called only inside the catalog writer transaction. */
internal class ProductSkus internal constructor(
    private val productSKURepository: ProductSKURepository,
    private val skuValueRepository: SKUValueRepository,
    private val optionValueRepository: OptionValueRepository,
) {

    fun createSkus(product: Product, skuDtos: List<SkuCreateDto>?): List<ProductSKU> {
        val savedSkus = ArrayList<ProductSKU>()

        if (skuDtos == null || skuDtos.isEmpty()) {
            return savedSkus
        }

        val optionValueIds = skuDtos.stream()
            .filter { skuDto -> skuDto.optionValueIds != null }
            .flatMap { skuDto -> skuDto.optionValueIds!!.stream() }
            .distinct()
            .sorted()
            .toList()
        val optionValuesById: Map<Long?, OptionValue> = if (optionValueIds.isEmpty()) {
            emptyMap()
        } else {
            optionValueRepository.findAllByIdWithLock(optionValueIds).stream()
                .collect(Collectors.toMap<OptionValue, Long?, OptionValue>({ it.id }, Function.identity()))
        }
        if (optionValuesById.size != optionValueIds.size) {
            throw IllegalArgumentException("존재하지 않는 옵션 값이 포함되어 있습니다.")
        }

        for (skuDto in skuDtos) {
            // SKU 저장
            val sku = ProductSKU.builder()
                .product(product)
                .skuCode(skuDto.skuCode)
                .price(skuDto.price)
                .stockQuantity(skuDto.stockQuantity)
                .build()
            productSKURepository.save(sku)

            savedSkus.add(sku)
            product.skus!!.add(sku)

            // 옵션 연결 (ID 기반)
            val requestedOptionValueIds = skuDto.optionValueIds
            if (requestedOptionValueIds != null && requestedOptionValueIds.isNotEmpty()) {
                for (optionValueId in requestedOptionValueIds) {
                    val optionValue = optionValuesById[optionValueId]

                    val skuValue = SKUValue.builder()
                        .productSKU(sku)
                        .optionValue(optionValue)
                        .build()
                    skuValueRepository.save(skuValue)
                    sku.skuValues.add(skuValue)
                }
            }
        }

        return savedSkus
    }
}
