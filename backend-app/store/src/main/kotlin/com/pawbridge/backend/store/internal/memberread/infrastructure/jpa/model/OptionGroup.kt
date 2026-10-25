package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/**
 * 옵션 그룹 (표준화된 마스터 테이블)
 * - 상품과 독립적으로 미리 등록
 * - 예: Color, Size, Material
 */
@Entity
@Table(name = "option_groups")
class OptionGroup(name: String?) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, length = 50, unique = true)
    var name: String? = name // e.g., Color, Size
        protected set

    @OneToMany(mappedBy = "optionGroup", cascade = [CascadeType.ALL], orphanRemoval = true)
    var optionValues: MutableList<OptionValue> = ArrayList()
        protected set

    protected constructor() : this(null)

    fun updateName(name: String?) {
        this.name = name
    }

    class OptionGroupBuilder internal constructor() {
        private var name: String? = null

        fun name(name: String?): OptionGroupBuilder = apply {
            this.name = name
        }

        fun build(): OptionGroup = OptionGroup(name)
    }

    companion object {
        @JvmStatic
        fun builder(): OptionGroupBuilder = OptionGroupBuilder()
    }
}
