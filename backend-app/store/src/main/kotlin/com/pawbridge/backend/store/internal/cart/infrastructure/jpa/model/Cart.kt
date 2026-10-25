package com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

// The protected no-arg constructor is the one Hibernate uses. It runs the property initializers
// (items = ArrayList), like the former protected Lombok constructor.
@Entity
@Table(name = "carts")
class Cart(userId: Long?) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, unique = true)
    var userId: Long? = userId
        protected set

    @OneToMany(mappedBy = "cart", cascade = [CascadeType.ALL], orphanRemoval = true)
    var items: MutableList<CartItem> = ArrayList()
        protected set

    protected constructor() : this(null)

    fun addItem(item: CartItem) {
        this.items.add(item)
        item.assignCart(this)
    }

    fun clearItems() {
        this.items.clear()
    }

    class CartBuilder internal constructor() {
        private var userId: Long? = null

        fun userId(userId: Long?): CartBuilder = apply {
            this.userId = userId
        }

        fun build(): Cart = Cart(userId)
    }

    companion object {
        @JvmStatic
        fun builder(): CartBuilder = CartBuilder()
    }
}
