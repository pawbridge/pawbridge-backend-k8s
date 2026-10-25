package com.pawbridge.backend.animal.internal.gallery.web

import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Semaphore

@RestController
class LostGalleryFeedController(
    private val feed: LostGalleryFeed,
    key: String?,
) {
    private val key: ByteArray = authenticationKey(key)
    private val permit = Semaphore(1)

    @GetMapping(value = ["/internal/animals/lost-gallery"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun snapshot(
        @RequestHeader(value = "X-Internal-Api-Key", required = false) supplied: String?,
        @RequestHeader(value = "If-None-Match", required = false) etag: String?,
    ): ResponseEntity<ByteArray?> {
        if (supplied == null || !MessageDigest.isEqual(key, supplied.toByteArray(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Cache-Control", "no-store").build()
        }
        if (!permit.tryAcquire()) {
            return unavailable()
        }
        try {
            val result = feed.snapshot(etag)
            return ResponseEntity.status(if (result.body() == null) 304 else 200)
                .header("Cache-Control", "no-store").eTag(result.etag()).body(result.body())
        } catch (error: Exception) {
            // Do not expose signed URLs, storage credentials, SQL details or partial responses.
            return unavailable()
        } finally {
            permit.release()
        }
    }

    private fun unavailable(): ResponseEntity<ByteArray?> {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Cache-Control", "no-store").header("Retry-After", "30").build()
    }

    private companion object {
        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun authenticationKey(key: String?): ByteArray {
            if (key == null || isBlank(key)) {
                throw IllegalArgumentException("Gallery feed authentication is required")
            }
            return key.toByteArray(StandardCharsets.UTF_8)
        }
    }
}
