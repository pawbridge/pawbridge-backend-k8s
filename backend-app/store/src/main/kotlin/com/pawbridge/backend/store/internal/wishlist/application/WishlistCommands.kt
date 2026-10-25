package com.pawbridge.backend.store.internal.wishlist.application

import java.time.LocalDateTime

/** Member identity comes from Root's authenticated caller, never an HTTP body/header userId. */
interface WishlistCommands {

    // options is mutable because the JDBC reader fills it while it walks the SKU option rows.
    @JvmRecord
    data class Added(
        val wishlistId: Long?,
        val userId: Long?,
        val skuId: Long?,
        val skuCode: String?,
        val productId: Long?,
        val productName: String?,
        val productImageUrl: String?,
        val price: Long?,
        val options: MutableMap<String, String>?,
        val createdAt: LocalDateTime?,
    )

    fun add(currentMemberId: Long, skuId: Long?): Added

    fun removeByMemberAndSku(currentMemberId: Long, skuId: Long)

    /** Owner-scoped and idempotent; the caller supplies the authenticated member identity. */
    fun removeById(currentMemberId: Long, wishlistId: Long)
}
