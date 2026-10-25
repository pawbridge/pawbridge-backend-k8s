package com.pawbridge.backend.community.internal.post.infrastructure.media

import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import java.util.regex.Pattern
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest

/** Explicit, best-effort deletion of media URLs read from the post itself. */
class S3PostMediaRemover(
    private val s3: S3Client,
    private val bucket: String,
    publicBaseUrl: String,
    objectPrefix: String,
) : PostMediaRemover {

    private val publicBaseUrl: String = removeTrailingSlashes(publicBaseUrl)
    private val objectPrefix: String = normalizeObjectPrefix(objectPrefix)

    override fun remove(imageUrls: List<String?>?) {
        if (imageUrls == null) {
            return
        }
        // Preserve legacy ordering and duplicate attempts. Validation failure is local to each URL.
        for (url in imageUrls) {
            try {
                val key = objectKey(url)
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
            } catch (failure: Exception) {
                // Do not pass URL, key, SDK text or the original Throwable to the logger.
                LOG.warn("Post media removal failed (exception message omitted): {}", failure.javaClass.name)
            }
        }
    }

    private fun objectKey(url: String?): String {
        val publicPrefix = "$publicBaseUrl/"
        if (url == null || !url.startsWith(publicPrefix)) {
            throw IllegalArgumentException("Unregistered post media URL")
        }
        val key = url.substring(publicPrefix.length)
        if (isBlank(key) || !key.startsWith(objectPrefix) || key.length == objectPrefix.length) {
            throw IllegalArgumentException("Post media key outside configured storage path")
        }
        // The uploader appends UUID.randomUUID() and the substring from the filename's last dot.
        // That suffix cannot contain another dot, so it cannot introduce a period-only path segment.
        // Preserve its percent signs, separators and control characters as one opaque SDK key:
        // parsing, decoding or normalizing the returned string could select a different object.
        if (UPLOADED_KEY.matcher(key.substring(objectPrefix.length)).matches()) {
            return key
        }
        if (key.contains("\\") ||
            key.contains("%") ||
            key.contains("?") ||
            key.contains("#") ||
            key.any { character -> character.code < 32 || character.code == 127 } ||
            key.split("/").any { segment -> segment.isEmpty() || segment == "." || segment == ".." }
        ) {
            throw IllegalArgumentException("Post media key outside configured storage path")
        }
        return key
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(S3PostMediaRemover::class.java)

        private val UPLOADED_KEY = Pattern.compile(
            "posts/(?:images|videos)/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}" +
                "(?:\\.[^.]*)?",
        )

        private val OBJECT_PREFIX = Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")

        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun normalizeObjectPrefix(prefix: String): String {
            if (prefix.isEmpty()) {
                return ""
            }
            if (!OBJECT_PREFIX.matcher(prefix).matches()) {
                throw IllegalArgumentException("Invalid post media object prefix")
            }
            return if (prefix.endsWith("/")) prefix else "$prefix/"
        }

        private fun removeTrailingSlashes(url: String): String {
            var end = url.length
            while (end > 0 && url[end - 1] == '/') {
                end--
            }
            return url.substring(0, end)
        }
    }
}
