package com.pawbridge.backend.store.internal.catalog.web

import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Boot must opt in explicitly; no component scan or security registration is supplied here. */
@RestController
class StoreCatalogController(private val catalog: StoreCatalog) {

    @GetMapping("/api/v1/products")
    fun search(
        @RequestParam(name = "keyword", required = false) keyword: String?,
        @RequestParam(name = "minPrice", required = false) minPrice: Long?,
        @RequestParam(name = "maxPrice", required = false) maxPrice: Long?,
        @RequestParam(name = "categoryId", required = false) categoryId: Long?,
        @RequestParam(name = "inStockOnly", defaultValue = "false") inStockOnly: Boolean?,
        @RequestParam(name = "sortBy", required = false) sortBy: String?,
        @RequestParam(name = "sortOrder", defaultValue = "desc") sortOrder: String?,
        @RequestParam(name = "page", defaultValue = "0") page: Int?,
        @RequestParam(name = "size", defaultValue = "20") size: Int?,
    ): StoreCatalog.SearchResult =
        catalog.search(
            StoreCatalog.Search(
                keyword,
                minPrice,
                maxPrice,
                categoryId,
                inStockOnly,
                sortBy,
                sortOrder,
                page,
                size,
            ),
        )

    @GetMapping("/api/v1/products/{productId}")
    fun product(@PathVariable("productId") productId: Long): StoreCatalog.Product = catalog.product(productId)

    @GetMapping("/api/v1/categories")
    fun categories(): List<StoreCatalog.Category> = catalog.categories()

    @GetMapping("/api/v1/option-groups")
    fun optionGroups(): List<StoreCatalog.OptionGroup> = catalog.optionGroups()

    @GetMapping("/api/v1/option-groups/{groupId}")
    fun optionGroup(@PathVariable("groupId") groupId: Long): StoreCatalog.OptionGroup = catalog.optionGroup(groupId)
}
