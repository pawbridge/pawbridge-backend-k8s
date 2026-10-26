package com.pawbridge.backend.store.internal.catalog.application

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

/** Read-only Store catalog contracts, retaining the existing Store JSON field names. */
interface StoreCatalog {

    @JvmRecord
    data class Search(
        val keyword: String?,
        val minPrice: Long?,
        val maxPrice: Long?,
        val categoryId: Long?,
        val inStockOnly: Boolean?,
        val sortBy: String?,
        val sortOrder: String?,
        val page: Int?,
        val size: Int?,
    )

    // The date-time components carry @JsonFormat on the constructor parameter, the field and the accessor,
    // which is where a Java record places an annotation written on a component.
    @JvmRecord
    data class Item(
        val id: Long?,
        val skuId: Long?,
        val name: String?,
        val optionName: String?,
        val description: String?,
        val imageUrl: String?,
        val price: Long?,
        val totalStock: Int?,
        val status: String?,
        @param:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @get:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        val createdAt: LocalDateTime?,
        @param:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @get:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        val updatedAt: LocalDateTime?,
    )

    @JvmRecord
    data class SearchResult(
        val items: List<Item>?,
        val totalCount: Long?,
        val currentPage: Int?,
        val totalPages: Int?,
        val hasNext: Boolean?,
    )

    // options is mutable because the JDBC reader fills it while it walks the SKU option rows.
    @JvmRecord
    data class Sku(
        val skuId: Long?,
        val skuCode: String?,
        val price: Long?,
        val stockQuantity: Int?,
        val options: MutableMap<String, String>?,
    )

    @JvmRecord
    data class Product(
        val productId: Long?,
        val name: String?,
        val description: String?,
        val imageUrl: String?,
        val status: String?,
        val categoryId: Long?,
        val categoryName: String?,
        val viewCount: Long?,
        @param:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @get:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        val createdAt: LocalDateTime?,
        @param:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @get:JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        val updatedAt: LocalDateTime?,
        val skus: List<Sku>?,
    )

    @JvmRecord
    data class Category(
        val id: Long?,
        val name: String?,
        val description: String?,
        val parentId: Long?,
        val children: List<Category>?,
    )

    @JvmRecord
    data class OptionValue(
        val id: Long?,
        val name: String?,
        val groupId: Long?,
        val groupName: String?,
    )

    // values is mutable because the JDBC reader fills it while it walks the option rows.
    @JvmRecord
    data class OptionGroup(
        val id: Long?,
        val name: String?,
        val values: MutableList<OptionValue>?,
    )

    fun search(request: Search): SearchResult

    fun product(productId: Long): Product

    fun categories(): List<Category>

    fun optionGroups(): List<OptionGroup>

    fun optionGroup(groupId: Long): OptionGroup
}
