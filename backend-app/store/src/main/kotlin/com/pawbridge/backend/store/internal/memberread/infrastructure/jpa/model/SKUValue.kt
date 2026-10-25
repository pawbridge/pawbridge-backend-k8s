package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "sku_values")
class SKUValue(
    productSKU: ProductSKU?,
    optionValue: OptionValue?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_sku_id", nullable = false)
    var productSKU: ProductSKU? = productSKU
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_value_id", nullable = false)
    var optionValue: OptionValue? = optionValue
        protected set

    protected constructor() : this(null, null)

    class SKUValueBuilder internal constructor() {
        private var productSKU: ProductSKU? = null
        private var optionValue: OptionValue? = null

        fun productSKU(productSKU: ProductSKU?): SKUValueBuilder = apply {
            this.productSKU = productSKU
        }

        fun optionValue(optionValue: OptionValue?): SKUValueBuilder = apply {
            this.optionValue = optionValue
        }

        fun build(): SKUValue = SKUValue(productSKU, optionValue)
    }

    companion object {
        @JvmStatic
        fun builder(): SKUValueBuilder = SKUValueBuilder()
    }
}
