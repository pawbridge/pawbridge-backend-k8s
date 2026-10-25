package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue

class OptionValueResponse internal constructor(
    val id: Long?,
    val name: String?,
    val groupId: Long?,
    val groupName: String?,
) {

    class OptionValueResponseBuilder internal constructor() {
        private var id: Long? = null
        private var name: String? = null
        private var groupId: Long? = null
        private var groupName: String? = null

        fun id(id: Long?): OptionValueResponseBuilder = apply {
            this.id = id
        }

        fun name(name: String?): OptionValueResponseBuilder = apply {
            this.name = name
        }

        fun groupId(groupId: Long?): OptionValueResponseBuilder = apply {
            this.groupId = groupId
        }

        fun groupName(groupName: String?): OptionValueResponseBuilder = apply {
            this.groupName = groupName
        }

        fun build(): OptionValueResponse = OptionValueResponse(id, name, groupId, groupName)
    }

    companion object {
        @JvmStatic
        fun builder(): OptionValueResponseBuilder = OptionValueResponseBuilder()

        @JvmStatic
        fun from(optionValue: OptionValue): OptionValueResponse =
            OptionValueResponse(
                id = optionValue.id,
                name = optionValue.name,
                groupId = optionValue.optionGroup!!.id,
                groupName = optionValue.optionGroup!!.name,
            )
    }
}
