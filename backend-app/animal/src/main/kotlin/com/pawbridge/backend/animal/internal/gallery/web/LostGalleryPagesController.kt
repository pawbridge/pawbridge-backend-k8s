package com.pawbridge.backend.animal.internal.gallery.web

import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Semaphore

@RestController
@RequestMapping("/internal/animals/lost-gallery/snapshots")
class LostGalleryPagesController(
    private val snapshots: LostGallerySnapshots,
    key: String?,
) {
    private val key: ByteArray = authenticationKey(key)
    private val permit = Semaphore(1)

    @PostMapping
    fun create(
        @RequestHeader(value = "X-Internal-Api-Key", required = false) supplied: String?,
        @RequestHeader(value = "If-None-Match", required = false) etag: String?,
        @RequestHeader(value = "X-Gallery-Request-Id", required = false) requestId: String?,
    ): ResponseEntity<*> {
        return handle(supplied) {
            val value = snapshots.create(requestId)
            val tag = "\"" + value.snapshotSha256() + "\""
            if (tag == etag) {
                snapshots.release(value.snapshotId())
                ResponseEntity.status(304).eTag(tag).build<Any>()
            } else {
                ResponseEntity.ok().eTag(tag).body(value)
            }
        }
    }

    @GetMapping("/{id}")
    fun describe(
        @PathVariable("id") id: String,
        @RequestHeader(value = "X-Internal-Api-Key", required = false) supplied: String?,
    ): ResponseEntity<*> {
        return handle(supplied) { ResponseEntity.ok(snapshots.describe(id)) }
    }

    @GetMapping(value = ["/{id}/pages"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun page(
        @PathVariable("id") id: String,
        @RequestParam("cursor") cursor: String,
        @RequestParam(value = "limit", defaultValue = "500") limit: Int,
        @RequestHeader(value = "X-Internal-Api-Key", required = false) supplied: String?,
    ): ResponseEntity<*> {
        return handle(supplied) {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(snapshots.page(id, cursor, limit))
        }
    }

    @DeleteMapping("/{id}")
    fun release(
        @PathVariable("id") id: String,
        @RequestHeader(value = "X-Internal-Api-Key", required = false) supplied: String?,
    ): ResponseEntity<*> {
        return handle(supplied) {
            snapshots.release(id)
            ResponseEntity.noContent().build<Any>()
        }
    }

    private fun handle(supplied: String?, operation: Callable<ResponseEntity<*>>): ResponseEntity<*> {
        if (supplied == null || !MessageDigest.isEqual(key, supplied.toByteArray(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build<Any>()
        }
        if (!permit.tryAcquire()) {
            return unavailable()
        }
        try {
            val response = operation.call()
            return ResponseEntity.status(response.statusCode).headers(response.headers)
                .header("Cache-Control", "no-store").body(response.body)
        } catch (error: LostGallerySnapshots.Gone) {
            return ResponseEntity.status(410).header("Cache-Control", "no-store").build<Any>()
        } catch (error: LostGallerySnapshots.BadCursor) {
            return ResponseEntity.badRequest().header("Cache-Control", "no-store").build<Any>()
        } catch (error: Exception) {
            return unavailable()
        } finally {
            permit.release()
        }
    }

    private fun unavailable(): ResponseEntity<*> {
        return ResponseEntity.status(503).header("Cache-Control", "no-store").header("Retry-After", "30").build<Any>()
    }

    private companion object {
        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun authenticationKey(key: String?): ByteArray {
            if (key == null || isBlank(key)) {
                throw IllegalArgumentException("Gallery authentication is required")
            }
            return key.toByteArray(StandardCharsets.UTF_8)
        }
    }
}
