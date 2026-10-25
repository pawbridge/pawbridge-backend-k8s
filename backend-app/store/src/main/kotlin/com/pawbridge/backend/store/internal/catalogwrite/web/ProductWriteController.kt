@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Path ids use java.lang.Long deliberately: kotlin.Long? makes the variable optional (a blank segment reaches the
// service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
// java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before this method runs.

/** Root explicitly registers this controller behind the original administrator boundary. */
@RestController
@RequestMapping("/api/v1/products")
class ProductWriteController(private val products: JpaCatalogProducts) {

    @PostMapping
    fun createProduct(@RequestBody request: ProductCreateRequest): ResponseEntity<ProductResponse> =
        ResponseEntity.status(201).body(products.createProduct(request))

    @PatchMapping("/{productId}")
    fun updateProduct(
        @PathVariable("productId") id: java.lang.Long,
        @RequestBody request: ProductUpdateRequest,
    ): ResponseEntity<ProductResponse> = ResponseEntity.ok(products.updateProduct(id.toLong(), request))

    @DeleteMapping("/{productId}")
    fun deleteProduct(@PathVariable("productId") id: java.lang.Long): ResponseEntity<Void> {
        products.deleteProduct(id.toLong())
        return ResponseEntity.noContent().build()
    }
}
