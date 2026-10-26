@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

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

// Path ids use java.lang.Long deliberately: kotlin.Long? makes the variable optional (a blank segment reaches the
// service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
// java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before the handler runs.

/** GET remains owned by StoreCatalogController; only original category write handlers are added. */
@RestController
@RequestMapping("/api/v1/categories")
class CategoryManagementController(private val categories: JpaCatalogCategories) {

    @PostMapping
    fun createCategory(@RequestBody request: CategoryCreateRequest): ResponseEntity<CategoryResponse> =
        ResponseEntity.status(201).body(categories.createCategory(request))

    @PutMapping("/{categoryId}")
    fun updateCategory(
        @PathVariable("categoryId") id: java.lang.Long,
        @RequestBody request: CategoryUpdateRequest,
    ): ResponseEntity<CategoryResponse> = ResponseEntity.ok(categories.updateCategory(id.toLong(), request))

    @DeleteMapping("/{categoryId}")
    fun deleteCategory(@PathVariable("categoryId") id: java.lang.Long): ResponseEntity<Void> {
        categories.deleteCategory(id.toLong())
        return ResponseEntity.noContent().build()
    }
}
