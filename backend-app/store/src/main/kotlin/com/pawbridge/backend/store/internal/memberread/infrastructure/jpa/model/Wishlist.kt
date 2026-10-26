package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "wishlists")
class Wishlist(
    userId: Long?,
    sku: ProductSKU?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var userId: Long? = userId
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sku_id", nullable = false)
    var sku: ProductSKU? = sku
        protected set

    protected constructor() : this(null, null)

    class WishlistBuilder internal constructor() {
        private var userId: Long? = null
        private var sku: ProductSKU? = null

        fun userId(userId: Long?): WishlistBuilder = apply {
            this.userId = userId
        }

        fun sku(sku: ProductSKU?): WishlistBuilder = apply {
            this.sku = sku
        }

        fun build(): Wishlist = Wishlist(userId, sku)
    }

    companion object {
        @JvmStatic
        fun builder(): WishlistBuilder = WishlistBuilder()
    }
}
