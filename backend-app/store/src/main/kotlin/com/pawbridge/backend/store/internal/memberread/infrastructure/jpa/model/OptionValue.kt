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
@Table(name = "option_values")
class OptionValue(
    optionGroup: OptionGroup?,
    name: String?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_group_id", nullable = false)
    var optionGroup: OptionGroup? = optionGroup
        protected set

    @Column(nullable = false, length = 50)
    var name: String? = name // e.g., Red, L
        protected set

    protected constructor() : this(null, null)

    fun updateName(name: String?) {
        this.name = name
    }

    class OptionValueBuilder internal constructor() {
        private var optionGroup: OptionGroup? = null
        private var name: String? = null

        fun optionGroup(optionGroup: OptionGroup?): OptionValueBuilder = apply {
            this.optionGroup = optionGroup
        }

        fun name(name: String?): OptionValueBuilder = apply {
            this.name = name
        }

        fun build(): OptionValue = OptionValue(optionGroup, name)
    }

    companion object {
        @JvmStatic
        fun builder(): OptionValueBuilder = OptionValueBuilder()
    }
}
