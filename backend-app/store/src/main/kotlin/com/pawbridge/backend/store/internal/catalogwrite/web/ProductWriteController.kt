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

/** Root explicitly registers this controller behind the original administrator boundary. */
@RestController
@RequestMapping("/api/v1/products")
class ProductWriteController(private val products: JpaCatalogProducts) {

    @PostMapping
    fun createProduct(@RequestBody request: ProductCreateRequest): ResponseEntity<ProductResponse> =
        ResponseEntity.status(201).body(products.createProduct(request))

    @PatchMapping("/{productId}")
    fun updateProduct(
        @PathVariable("productId") id: Long?,
        @RequestBody request: ProductUpdateRequest,
    ): ResponseEntity<ProductResponse> = ResponseEntity.ok(products.updateProduct(id, request))

    @DeleteMapping("/{productId}")
    fun deleteProduct(@PathVariable("productId") id: Long?): ResponseEntity<Void> {
        products.deleteProduct(id)
        return ResponseEntity.noContent().build()
    }
}
