package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product

class ProductResponse internal constructor(
    val id: Long?,
    val name: String?,
    val description: String?,
    val imageUrl: String?,
    val status: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val skus: List<SkuDetailDto>?,
) {

    class ProductResponseBuilder internal constructor() {
        private var id: Long? = null
        private var name: String? = null
        private var description: String? = null
        private var imageUrl: String? = null
        private var status: String? = null
        private var categoryId: Long? = null
        private var categoryName: String? = null
        private var skus: List<SkuDetailDto>? = null

        fun id(id: Long?): ProductResponseBuilder = apply {
            this.id = id
        }

        fun name(name: String?): ProductResponseBuilder = apply {
            this.name = name
        }

        fun description(description: String?): ProductResponseBuilder = apply {
            this.description = description
        }

        fun imageUrl(imageUrl: String?): ProductResponseBuilder = apply {
            this.imageUrl = imageUrl
        }

        fun status(status: String?): ProductResponseBuilder = apply {
            this.status = status
        }

        fun categoryId(categoryId: Long?): ProductResponseBuilder = apply {
            this.categoryId = categoryId
        }

        fun categoryName(categoryName: String?): ProductResponseBuilder = apply {
            this.categoryName = categoryName
        }

        fun skus(skus: List<SkuDetailDto>?): ProductResponseBuilder = apply {
            this.skus = skus
        }

        fun build(): ProductResponse =
            ProductResponse(id, name, description, imageUrl, status, categoryId, categoryName, skus)
    }

    companion object {
        @JvmStatic
        fun builder(): ProductResponseBuilder = ProductResponseBuilder()

        @JvmStatic
        fun from(product: Product): ProductResponse {
            val category = product.category
            return ProductResponse(
                id = product.id,
                name = product.name,
                description = product.description,
                imageUrl = product.imageUrl,
                status = product.status!!.name,
                categoryId = category?.id,
                categoryName = category?.name,
                skus = product.skus!!.map { SkuDetailDto.from(it) },
            )
        }
    }
}
