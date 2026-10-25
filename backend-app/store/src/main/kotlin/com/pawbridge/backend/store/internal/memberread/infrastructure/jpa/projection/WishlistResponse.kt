package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.projection

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist
import java.time.LocalDateTime

/**
 * 찜 목록 응답 DTO
 * - 마이페이지용
 * - SKU 기반 (가격, 옵션 정보 포함)
 */
data class WishlistResponse(
    var wishlistId: Long? = null,
    var skuId: Long? = null,
    var skuCode: String? = null,
    var productId: Long? = null,
    var productName: String? = null,
    var productDescription: String? = null,
    var productImageUrl: String? = null,
    var productStatus: String? = null,
    var price: Long? = null,
    var options: Map<String?, String?>? = null,
    var categoryId: Long? = null,
    var categoryName: String? = null,
    var createdAt: LocalDateTime? = null,
) {

    companion object {
        @JvmStatic
        fun from(wishlist: Wishlist): WishlistResponse {
            val sku = wishlist.sku!!

            // 옵션 정보 추출 (Java HashMap처럼 null 키·값도 그대로 담는다)
            val optionMap = HashMap<String?, String?>()
            for (skuValue in sku.skuValues) {
                optionMap[skuValue.optionValue!!.optionGroup!!.name] = skuValue.optionValue!!.name
            }

            val product = sku.product!!
            val category = product.category

            return WishlistResponse(
                wishlistId = wishlist.id,
                skuId = sku.id,
                skuCode = sku.skuCode,
                productId = product.id,
                productName = product.name,
                productDescription = product.description,
                productImageUrl = product.imageUrl,
                productStatus = product.status!!.name,
                price = sku.price,
                options = optionMap,
                categoryId = category?.id,
                categoryName = category?.name,
                createdAt = wishlist.createdAt,
            )
        }
    }
}
