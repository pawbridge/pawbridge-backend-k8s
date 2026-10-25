package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU

class SkuDetailDto internal constructor(
    val skuId: Long?,
    val skuCode: String?,
    val price: Long?,
    val stockQuantity: Int?,
    val options: Map<String?, String?>?, // 예: "Color" -> "Red"
) {

    class SkuDetailDtoBuilder internal constructor() {
        private var skuId: Long? = null
        private var skuCode: String? = null
        private var price: Long? = null
        private var stockQuantity: Int? = null
        private var options: Map<String?, String?>? = null

        fun skuId(skuId: Long?): SkuDetailDtoBuilder = apply {
            this.skuId = skuId
        }

        fun skuCode(skuCode: String?): SkuDetailDtoBuilder = apply {
            this.skuCode = skuCode
        }

        fun price(price: Long?): SkuDetailDtoBuilder = apply {
            this.price = price
        }

        fun stockQuantity(stockQuantity: Int?): SkuDetailDtoBuilder = apply {
            this.stockQuantity = stockQuantity
        }

        fun options(options: Map<String?, String?>?): SkuDetailDtoBuilder = apply {
            this.options = options
        }

        fun build(): SkuDetailDto = SkuDetailDto(skuId, skuCode, price, stockQuantity, options)
    }

    companion object {
        @JvmStatic
        fun builder(): SkuDetailDtoBuilder = SkuDetailDtoBuilder()

        @JvmStatic
        fun from(sku: ProductSKU): SkuDetailDto {
            // Java HashMap accepts null keys and values, so the map keeps them nullable.
            val optionMap = HashMap<String?, String?>()
            for (skuValue in sku.skuValues) {
                optionMap[skuValue.optionValue!!.optionGroup!!.name] = skuValue.optionValue!!.name
            }

            return SkuDetailDto(
                skuId = sku.id,
                skuCode = sku.skuCode,
                price = sku.price,
                stockQuantity = sku.stockQuantity,
                options = optionMap,
            )
        }
    }
}
