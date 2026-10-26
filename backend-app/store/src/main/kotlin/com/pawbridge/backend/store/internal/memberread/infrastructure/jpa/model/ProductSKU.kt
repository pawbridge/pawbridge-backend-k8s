package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(name = "product_skus")
class ProductSKU(
    product: Product?,
    skuCode: String?,
    price: Long?,
    stockQuantity: Int?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    var product: Product? = product
        protected set

    @Column(nullable = false, unique = true, length = 50)
    var skuCode: String? = skuCode // 예: TSHIRT-RED-L
        protected set

    @Column(nullable = false)
    var price: Long? = price
        protected set

    @Column(nullable = false)
    var stockQuantity: Int? = stockQuantity
        protected set

    @OneToMany(mappedBy = "productSKU", cascade = [CascadeType.ALL], orphanRemoval = true)
    var skuValues: MutableList<SKUValue> = ArrayList()
        protected set

    protected constructor() : this(null, null, null, null)

    // 비즈니스 로직: 재고 감소
    fun decreaseStock(quantity: Int) {
        if (this.stockQuantity!! < quantity) {
            throw IllegalStateException("재고 부족") // Custom Exception 처리 필요
        }
        this.stockQuantity = this.stockQuantity!! - quantity
    }

    // 비즈니스 로직: 재고 증가 (롤백용)
    fun increaseStock(quantity: Int) {
        this.stockQuantity = this.stockQuantity!! + quantity
    }

    fun generateOptionName(): String {
        if (this.skuValues.isEmpty()) {
            return ""
        }
        return this.skuValues
            .map { skuValue ->
                skuValue.optionValue!!.optionGroup!!.name + ": " + skuValue.optionValue!!.name
            }
            .sorted()
            .joinToString(", ")
    }

    fun updatePrice(price: Long?) {
        this.price = price
    }

    fun updateStock(stockQuantity: Int?) {
        this.stockQuantity = stockQuantity
    }

    class ProductSKUBuilder internal constructor() {
        private var product: Product? = null
        private var skuCode: String? = null
        private var price: Long? = null
        private var stockQuantity: Int? = null

        fun product(product: Product?): ProductSKUBuilder = apply {
            this.product = product
        }

        fun skuCode(skuCode: String?): ProductSKUBuilder = apply {
            this.skuCode = skuCode
        }

        fun price(price: Long?): ProductSKUBuilder = apply {
            this.price = price
        }

        fun stockQuantity(stockQuantity: Int?): ProductSKUBuilder = apply {
            this.stockQuantity = stockQuantity
        }

        fun build(): ProductSKU = ProductSKU(product, skuCode, price, stockQuantity)
    }

    companion object {
        @JvmStatic
        fun builder(): ProductSKUBuilder = ProductSKUBuilder()
    }
}
