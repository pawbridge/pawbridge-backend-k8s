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
        requireWithin(file?.size ?: 0L, FILE_LIMIT_BYTES)
        return images.uploadImage(file)
    }

    fun uploadImages(files: Array<MultipartFile?>?): List<String> {
        var total = 0L
        if (files != null) {
            for (file in files) {
                val size = file?.size ?: 0L
                requireWithin(size, FILE_LIMIT_BYTES)
                total += size
            }
        }
        requireWithin(total, MULTIPLE_REQUEST_LIMIT_BYTES)

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
        // Each photo may be up to 10MB. The original service also capped the whole multiple-upload request
        // at 10MB, so a few ordinary photos together were rejected; the request now fits five full-size
        // photos plus multipart overhead.
        private const val FILE_LIMIT_BYTES = 10L * 1024 * 1024
        private const val MULTIPLE_REQUEST_LIMIT_BYTES = 55L * 1024 * 1024

        private fun requireWithin(bytes: Long, limit: Long) {
            if (bytes > limit) {
                throw AnimalImageTooLargeException()
            }
        }
    }
}
