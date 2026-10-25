package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category

class CategoryResponse internal constructor(
    val id: Long?,
    val name: String?,
    val description: String?,
    val parentId: Long?,
    val children: List<CategoryResponse>?,
) {

    class CategoryResponseBuilder internal constructor() {
        private var id: Long? = null
        private var name: String? = null
        private var description: String? = null
        private var parentId: Long? = null
        private var children: List<CategoryResponse>? = null

        fun id(id: Long?): CategoryResponseBuilder = apply {
            this.id = id
        }

        fun name(name: String?): CategoryResponseBuilder = apply {
            this.name = name
        }

        fun description(description: String?): CategoryResponseBuilder = apply {
            this.description = description
        }

        fun parentId(parentId: Long?): CategoryResponseBuilder = apply {
            this.parentId = parentId
        }

        fun children(children: List<CategoryResponse>?): CategoryResponseBuilder = apply {
            this.children = children
        }

        fun build(): CategoryResponse = CategoryResponse(id, name, description, parentId, children)
    }

    companion object {
        @JvmStatic
        fun builder(): CategoryResponseBuilder = CategoryResponseBuilder()

        @JvmStatic
        fun from(category: Category): CategoryResponse {
            val parent = category.parent
            return CategoryResponse(
                id = category.id,
                name = category.name,
                description = category.description,
                parentId = parent?.id,
                children = category.children.map { from(it) },
            )
        }
    }
}
