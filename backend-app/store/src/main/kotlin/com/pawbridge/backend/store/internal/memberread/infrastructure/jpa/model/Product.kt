package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

// The former Lombok @AllArgsConstructor is the primary constructor; the protected no-arg constructor
// (Hibernate's) applies the former @Builder.Default values (viewCount = 0, skus = ArrayList).
@Entity
@Table(name = "products")
class Product(
    id: Long?,
    name: String?,
    description: String?,
    imageUrl: String?,
    status: ProductStatus?,
    viewCount: Long?,
    category: Category?,
    skus: MutableList<ProductSKU>?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    @Column(nullable = false, length = 100)
    var name: String? = name
        protected set

    @Column(columnDefinition = "TEXT")
    var description: String? = description
        protected set

    @Column(length = 255)
    var imageUrl: String? = imageUrl
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProductStatus? = status
        protected set

    @Column(nullable = false)
    var viewCount: Long? = viewCount
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: Category? = category
        protected set

    // OptionGroup은 이제 독립 테이블이므로 직접 연관관계 제거
    // SKU를 통해 OptionValue와 연결됨

    @OneToMany(mappedBy = "product", cascade = [CascadeType.ALL], orphanRemoval = true)
    var skus: MutableList<ProductSKU>? = skus
        protected set

    protected constructor() : this(null, null, null, null, null, DEFAULT_VIEW_COUNT, null, ArrayList())

    fun updateName(name: String?) {
        this.name = name
    }

    fun updateDescription(description: String?) {
        this.description = description
    }

    fun updateImageUrl(imageUrl: String?) {
        this.imageUrl = imageUrl
    }

    fun updateStatus(status: ProductStatus?) {
        this.status = status
    }

    fun assignCategory(category: Category?) {
        this.category = category
    }

    class ProductBuilder internal constructor() {
        private var id: Long? = null
        private var name: String? = null
        private var description: String? = null
        private var imageUrl: String? = null
        private var status: ProductStatus? = null
        private var viewCount: Long? = DEFAULT_VIEW_COUNT
        private var category: Category? = null
        private var skus: MutableList<ProductSKU>? = null
        private var skusAssigned = false

        fun id(id: Long?): ProductBuilder = apply {
            this.id = id
        }

        fun name(name: String?): ProductBuilder = apply {
            this.name = name
        }

        fun description(description: String?): ProductBuilder = apply {
            this.description = description
        }

        fun imageUrl(imageUrl: String?): ProductBuilder = apply {
            this.imageUrl = imageUrl
        }

        fun status(status: ProductStatus?): ProductBuilder = apply {
            this.status = status
        }

        fun viewCount(viewCount: Long?): ProductBuilder = apply {
            this.viewCount = viewCount
        }

        fun category(category: Category?): ProductBuilder = apply {
            this.category = category
        }

        fun skus(skus: MutableList<ProductSKU>?): ProductBuilder = apply {
            this.skus = skus
            this.skusAssigned = true
        }

        // The former @Builder.Default created a fresh list for every build() that did not set skus.
        fun build(): Product = Product(
            id,
            name,
            description,
            imageUrl,
            status,
            viewCount,
            category,
            if (skusAssigned) skus else ArrayList(),
        )
    }

    companion object {
        private const val DEFAULT_VIEW_COUNT = 0L

        @JvmStatic
        fun builder(): ProductBuilder = ProductBuilder()
    }
}
