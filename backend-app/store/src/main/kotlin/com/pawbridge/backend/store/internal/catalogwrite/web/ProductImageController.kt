package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/images")
class ProductImageController(private val images: S3ProductImages) {

    @PostMapping
    fun uploadImage(@RequestParam("file") file: MultipartFile): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok(java.util.Map.of("imageUrl", images.uploadImage(file)))
}
