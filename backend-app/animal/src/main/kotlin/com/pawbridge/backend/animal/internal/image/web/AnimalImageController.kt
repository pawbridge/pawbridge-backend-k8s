package com.pawbridge.backend.animal.internal.image.web

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Original ImageController routes and response maps; only the verified JWT member is trusted. */
@RestController
@RequestMapping("/api/v1/animals/images")
class AnimalImageController(
    private val images: AnimalImageService,
) {

    @PostMapping
    fun uploadImage(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<Map<String, String>> {
        val imageUrl = images.uploadImage(verifiedUserId, file)

        val response = HashMap<String, String>()
        response["imageUrl"] = imageUrl

        return ResponseEntity.ok(response)
    }

    @PostMapping("/multiple")
    fun uploadMultipleImages(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @RequestParam("files") files: Array<MultipartFile?>,
    ): ResponseEntity<Map<String, Any>> {
        val imageUrls = images.uploadImages(verifiedUserId, files)

        val response = HashMap<String, Any>()
        response["imageUrls"] = imageUrls
        response["count"] = imageUrls.size

        return ResponseEntity.ok(response)
    }

    @DeleteMapping
    fun deleteImage(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @RequestParam("imageUrl") imageUrl: String,
    ): ResponseEntity<Map<String, String>> {
        images.deleteImage(verifiedUserId, imageUrl)

        val response = HashMap<String, String>()
        response["message"] = "이미지가 삭제되었습니다."

        return ResponseEntity.ok(response)
    }
}
