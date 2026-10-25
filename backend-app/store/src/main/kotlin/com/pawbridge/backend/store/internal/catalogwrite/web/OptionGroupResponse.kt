package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup

class OptionGroupResponse internal constructor(
    val id: Long?,
    val name: String?,
    val values: List<OptionValueResponse>?,
) {

    class OptionGroupResponseBuilder internal constructor() {
        private var id: Long? = null
        private var name: String? = null
        private var values: List<OptionValueResponse>? = null

        fun id(id: Long?): OptionGroupResponseBuilder = apply {
            this.id = id
        }

        fun name(name: String?): OptionGroupResponseBuilder = apply {
            this.name = name
        }

        fun values(values: List<OptionValueResponse>?): OptionGroupResponseBuilder = apply {
            this.values = values
        }

        fun build(): OptionGroupResponse = OptionGroupResponse(id, name, values)
    }

    companion object {
        @JvmStatic
        fun builder(): OptionGroupResponseBuilder = OptionGroupResponseBuilder()

        @JvmStatic
        fun from(optionGroup: OptionGroup): OptionGroupResponse =
            OptionGroupResponse(
                id = optionGroup.id,
                name = optionGroup.name,
                values = optionGroup.optionValues.map { OptionValueResponse.from(it) },
            )
    }
}
