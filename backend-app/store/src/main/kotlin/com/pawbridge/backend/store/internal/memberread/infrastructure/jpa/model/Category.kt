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

// The protected no-arg constructor is the one Hibernate uses. It runs the property initializers
// (children = ArrayList), like the former protected Lombok constructor.
@Entity
@Table(name = "categories")
class Category(
    name: String?,
    description: String?,
    parent: Category?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, unique = true)
    var name: String? = name
        protected set

    @Column(length = 500)
    var description: String? = description
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: Category? = parent
        protected set

    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL])
    var children: MutableList<Category> = ArrayList()
        protected set

    protected constructor() : this(null, null, null)

    /**
     * 카테고리 정보 수정
     * @param name 새로운 카테고리명
     * @param parent 새로운 부모 카테고리 (null이면 루트 카테고리)
     */
    fun update(name: String?, description: String?, parent: Category?) {
        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        if (name != null && !name.all { Character.isWhitespace(it) }) {
            this.name = name
        }
        this.description = description
        this.parent = parent
    }

    class CategoryBuilder internal constructor() {
        private var name: String? = null
        private var description: String? = null
        private var parent: Category? = null

        fun name(name: String?): CategoryBuilder = apply {
            this.name = name
        }

        fun description(description: String?): CategoryBuilder = apply {
            this.description = description
        }

        fun parent(parent: Category?): CategoryBuilder = apply {
            this.parent = parent
        }

        fun build(): Category = Category(name, description, parent)
    }

    companion object {
        @JvmStatic
        fun builder(): CategoryBuilder = CategoryBuilder()
    }
}
