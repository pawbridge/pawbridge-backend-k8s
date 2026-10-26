package com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.BaseEntity
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
@Table(name = "cart_items")
class CartItem(
    skuId: Long?,
    quantity: Int?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    var cart: Cart? = null
        protected set

    @Column(name = "sku_id", nullable = false)
    var skuId: Long? = skuId
        protected set

    @Column(name = "product_sku_id", nullable = false)
    var productSkuId: Long? = skuId
        protected set

    @Column(nullable = false)
    var quantity: Int? = quantity
        protected set

    protected constructor() : this(null, null)

    fun assignCart(cart: Cart?) {
        this.cart = cart
    }

    fun updateQuantity(quantity: Int?) {
        this.quantity = quantity
    }

    class CartItemBuilder internal constructor() {
        private var skuId: Long? = null
        private var quantity: Int? = null

        fun skuId(skuId: Long?): CartItemBuilder = apply {
            this.skuId = skuId
        }

        fun quantity(quantity: Int?): CartItemBuilder = apply {
            this.quantity = quantity
        }

        fun build(): CartItem = CartItem(skuId, quantity)
    }

    companion object {
        @JvmStatic
        fun builder(): CartItemBuilder = CartItemBuilder()
    }
}
