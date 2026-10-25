package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** GET remains owned by StoreCatalogController; only original category write handlers are added. */
@RestController
@RequestMapping("/api/v1/categories")
class CategoryManagementController(private val categories: JpaCatalogCategories) {

    @PostMapping
    fun createCategory(@RequestBody request: CategoryCreateRequest): ResponseEntity<CategoryResponse> =
        ResponseEntity.status(201).body(categories.createCategory(request))

    @PutMapping("/{categoryId}")
    fun updateCategory(
        @PathVariable("categoryId") id: Long?,
        @RequestBody request: CategoryUpdateRequest,
    ): ResponseEntity<CategoryResponse> = ResponseEntity.ok(categories.updateCategory(id, request))

    @DeleteMapping("/{categoryId}")
    fun deleteCategory(@PathVariable("categoryId") id: Long?): ResponseEntity<Void> {
        categories.deleteCategory(id)
        return ResponseEntity.noContent().build()
    }
}
