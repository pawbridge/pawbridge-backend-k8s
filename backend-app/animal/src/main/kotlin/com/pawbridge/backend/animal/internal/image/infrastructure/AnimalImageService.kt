package com.pawbridge.backend.animal.internal.image.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile

/**
 * Original image routes behind the former Gateway checks. Single upload and delete matched its non-USER
 * path rule; the two-segment multiple upload matched no role rule and stays open to any signed member.
 */
class AnimalImageService(
    private val images: S3AnimalImages,
    private val actors: AnimalCommandActors,
    private val writes: TransactionTemplate,
) {

    fun uploadImage(verifiedUserId: Long?, file: MultipartFile?): String {
        requireNonUser(verifiedUserId)
        requireOriginalRequestLimit(file?.size ?: 0L)
        return images.uploadImage(file)
    }

    fun uploadImages(files: Array<MultipartFile?>?): List<String> {
        var total = 0L
        if (files != null) {
            for (file in files) {
                total += file?.size ?: 0L
            }
        }
        requireOriginalRequestLimit(total)

        if (files == null || files.isEmpty()) {
            throw IllegalArgumentException("업로드할 파일이 없습니다.")
        }

        if (files.size > 5) {
            throw IllegalArgumentException("최대 5개의 이미지만 업로드할 수 있습니다.")
        }

        val imageUrls = ArrayList<String>()
        for (file in files) {
            imageUrls.add(images.uploadImage(file))
        }
        return imageUrls
    }

    fun deleteImage(verifiedUserId: Long?, imageUrl: String?) {
        requireNonUser(verifiedUserId)
        images.deleteImage(imageUrl)
    }

    private fun requireNonUser(verifiedUserId: Long?) {
        if (verifiedUserId == null) {
            throw AnimalImageForbiddenException()
        }
        // Commit the current-account lookup before storage I/O so the member row lock is not held.
        val actor: Actor? = writes.execute { _ -> actors.find(verifiedUserId) }
        if (actor == null ||
            actor.userId != verifiedUserId ||
            !actor.active ||
            actor.deleted ||
            !("ROLE_ADMIN" == actor.role || "ROLE_SHELTER" == actor.role)
        ) {
            throw AnimalImageForbiddenException()
        }
    }

    private companion object {
        // The original service rejected multipart requests above 10MB before any controller code ran.
        private const val ORIGINAL_REQUEST_LIMIT_BYTES = 10L * 1024 * 1024

        private fun requireOriginalRequestLimit(bytes: Long) {
            if (bytes > ORIGINAL_REQUEST_LIMIT_BYTES) {
                throw AnimalImageTooLargeException()
            }
        }
    }
}
