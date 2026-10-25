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
@Table(name = "order_items")
class OrderItem(
    order: Order?,
    productSKU: ProductSKU?,
    productName: String?,
    skuCode: String?,
    price: Long?,
    quantity: Int?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    var order: Order? = order
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_sku_id", nullable = false)
    var productSKU: ProductSKU? = productSKU
        protected set

    @Column(nullable = false, length = 100)
    var productName: String? = productName // Snapshot
        protected set

    @Column(nullable = false, length = 50)
    var skuCode: String? = skuCode // Snapshot
        protected set

    @Column(nullable = false)
    var price: Long? = price // Snapshot
        protected set

    @Column(nullable = false)
    var quantity: Int? = quantity
        protected set

    protected constructor() : this(null, null, null, null, null, null)

    class OrderItemBuilder internal constructor() {
        private var order: Order? = null
        private var productSKU: ProductSKU? = null
        private var productName: String? = null
        private var skuCode: String? = null
        private var price: Long? = null
        private var quantity: Int? = null

        fun order(order: Order?): OrderItemBuilder = apply {
            this.order = order
        }

        fun productSKU(productSKU: ProductSKU?): OrderItemBuilder = apply {
            this.productSKU = productSKU
        }

        fun productName(productName: String?): OrderItemBuilder = apply {
            this.productName = productName
        }

        fun skuCode(skuCode: String?): OrderItemBuilder = apply {
            this.skuCode = skuCode
        }

        fun price(price: Long?): OrderItemBuilder = apply {
            this.price = price
        }

        fun quantity(quantity: Int?): OrderItemBuilder = apply {
            this.quantity = quantity
        }

        fun build(): OrderItem = OrderItem(order, productSKU, productName, skuCode, price, quantity)
    }

    companion object {
        @JvmStatic
        fun builder(): OrderItemBuilder = OrderItemBuilder()
    }
}
