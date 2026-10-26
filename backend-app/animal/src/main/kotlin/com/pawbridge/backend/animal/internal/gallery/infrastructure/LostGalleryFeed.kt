package com.pawbridge.backend.animal.internal.gallery.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.support.TransactionOperations
import java.security.MessageDigest
import java.util.HexFormat
import java.util.function.Consumer
import java.util.function.Function

/**
 * Complete read-only archive snapshot; never advances a DB cursor or writes an object.
 *
 * `jdbc` and `signer` are nullable because the unit tests build the feed without one of them; each is only
 * dereferenced by the path that needs it, so a missing one fails with an NPE there, exactly like the Java fields did.
 */
open class LostGalleryFeed(
    private val jdbc: JdbcTemplate?,
    private val mapper: ObjectMapper,
    private val signer: Function<String, String>?,
    reads: TransactionOperations,
    streamReads: TransactionOperations,
) {
    private val reads: TransactionOperations = java.util.Objects.requireNonNull(reads)
    private val streamReads: TransactionOperations = java.util.Objects.requireNonNull(streamReads)

    // The nested types below are package-private in Java; Kotlin has no package-private, so they are public here.
    @JvmRecord
    data class Result(
        val etag: String?,
        val body: ByteArray?,
    )

    @JvmRecord
    data class StoredPhoto(
        val sha256: String?,
        val key: String?,
        val bytes: Long,
        val mime: String?,
    )

    @JvmRecord
    data class Photo(
        val sha256: String?,
        val url: String?,
        val bytes: Long,
        val mime: String?,
    )

    @JvmRecord
    data class Content(
        val records: List<Map<String, Any?>>,
        val photos: List<StoredPhoto>,
    )

    @JvmRecord
    data class Snapshot(
        val complete: Boolean,
        val count: Int,
        val records: List<Map<String, Any?>>,
        val photos: List<Photo>,
    )

    fun snapshot(previousEtag: String?): Result {
        val content = reads.execute { readContent() }!!
        if (content.records.size > 500) {
            throw IllegalStateException("Legacy feed exceeds page limit")
        }
        val etag = fingerprint(json(content))
        if (etag == previousEtag) {
            return Result(etag, null)
        }
        val photos = content.photos.map { photo ->
            val url = signer!!.apply(photo.key!!)
            Photo(photo.sha256, url, photo.bytes, photo.mime)
        }
        val body = json(Snapshot(true, content.records.size, content.records, photos))
        if (body.size > LostGallerySnapshots.PAGE_BYTES) {
            throw IllegalStateException("Gallery snapshot exceeds transfer limit")
        }
        return Result(etag, body)
    }

    // Package-private in Java, where the unit test overrides it; protected is the closest Kotlin visibility
    // (a protected JVM method is still callable from the same package).
    protected open fun readContent(): Content {
        return jdbc!!.query(
            PreparedStatementCreator { connection ->
                val statement = connection.prepareStatement(SQL)
                statement.queryTimeout = 20
                // Driver-specific cursor batch; enclosing read transaction enables PostgreSQL streaming.
                statement.fetchSize = 200
                statement
            },
            ResultSetExtractor { result ->
                val records = ArrayList<Map<String, Any?>>()
                val photos = LinkedHashMap<String, StoredPhoto>()
                var serializedBytes = 0L
                while (result.next()) {
                    if (records.size == 500) {
                        throw IllegalStateException("Gallery record limit exceeded")
                    }
                    val photo = StoredPhoto(
                        result.getString("stored_sha256"),
                        result.getString("object_key"),
                        result.getLong("stored_bytes"),
                        result.getString("content_type"),
                    )
                    validatePhoto(photo)
                    val previous = photos.putIfAbsent(photo.sha256!!, photo)
                    if (previous != null && (previous.bytes != photo.bytes || previous.mime != photo.mime)) {
                        throw IllegalStateException("Conflicting photo integrity metadata")
                    }
                    val row = LinkedHashMap<String, Any?>()
                    row["id"] = result.getLong("id")
                    row["species"] = result.getString("species")
                    row["source_sha256"] = photo.sha256
                    for (column in LostGallerySnapshots.METADATA) {
                        val value: String? = result.getString(column)
                        if (value != null && value.length > 10000) {
                            throw IllegalStateException("Gallery metadata exceeds limit")
                        }
                        row[column] = value
                    }
                    serializedBytes += json(row).size + 1200L // Reserve space for each bounded signed URL.
                    if (serializedBytes > LostGallerySnapshots.PAGE_BYTES) {
                        throw IllegalStateException("Gallery snapshot exceeds transfer limit")
                    }
                    records.add(row)
                }
                if (records.isEmpty()) {
                    throw IllegalStateException("Empty gallery snapshot is not publishable")
                }
                Content(java.util.List.copyOf(records), java.util.List.copyOf(photos.values))
            },
        )!!
    }

    /** One consistent DB read, consumed row by row before the snapshot is published. */
    fun streamEntries(consumer: Consumer<LostGallerySnapshots.Entry>) {
        streamReads.executeWithoutResult { streamRows(consumer) }
    }

    private fun streamRows(consumer: Consumer<LostGallerySnapshots.Entry>) {
        val conflicts = "SELECT stored_sha256 FROM (" + SQL + ") source GROUP BY stored_sha256 " +
            "HAVING MIN(stored_bytes) <> MAX(stored_bytes) OR MIN(content_type) <> MAX(content_type) LIMIT 1"
        val conflict: Boolean? = jdbc!!.query(
            PreparedStatementCreator { connection ->
                val statement = connection.prepareStatement(conflicts)
                statement.queryTimeout = 20
                statement
            },
            ResultSetExtractor<Boolean> { result -> result.next() },
        )
        if (conflict == true) {
            throw IllegalStateException("Conflicting photo integrity metadata")
        }
        jdbc.query(
            PreparedStatementCreator { connection ->
                val statement = connection.prepareStatement(SQL)
                statement.queryTimeout = 20
                statement.fetchSize = 200
                statement
            },
            RowCallbackHandler { result ->
                val photo = StoredPhoto(
                    result.getString("stored_sha256"),
                    result.getString("object_key"),
                    result.getLong("stored_bytes"),
                    result.getString("content_type"),
                )
                validatePhoto(photo)
                val row = LinkedHashMap<String, Any?>()
                row["id"] = result.getLong("id")
                row["species"] = result.getString("species")
                row["source_sha256"] = photo.sha256
                for (field in LostGallerySnapshots.METADATA) {
                    row[field] = result.getString(field)
                }
                consumer.accept(LostGallerySnapshots.Entry(row, photo))
            },
        )
    }

    private fun json(value: Any): ByteArray {
        try {
            return mapper.writeValueAsBytes(value)
        } catch (error: Exception) {
            throw IllegalStateException("Cannot serialize gallery snapshot")
        }
    }

    private fun fingerprint(value: ByteArray): String {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)) + "\""
        } catch (error: Exception) {
            throw IllegalStateException("Cannot fingerprint gallery snapshot")
        }
    }

    companion object {
        // Package-private in Java; public here because Kotlin has no package-private (tests and the snapshot store use them).
        const val MAX_RECORDS: Int = 100_000
        const val MAX_BODY_BYTES: Int = 128 * 1024 * 1024

        // Java text block: the closing delimiter is on its own line, so the text ends with a line feed.
        @JvmField
        val SQL: String = """
            SELECT a.id, a.species, a.status, a.happen_date, a.happen_place, a.color, a.special_mark, a.description,
                   p.object_key, p.stored_sha256, p.stored_bytes, p.content_type
            FROM pawbridge_animal.apms_photo_archive p JOIN pawbridge_animal.animals a ON a.id = p.animal_id
            WHERE p.state = 'READY' AND p.slot = 1
              AND p.source_url = p.archived_source_url AND p.source_url = TRIM(a.image_url)
              AND a.species IN ('DOG', 'CAT')
            ORDER BY a.id LIMIT 100001
        """.trimIndent() + "\n"

        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        private val OBJECT_KEY_PATTERN = Regex("apms/photos/[a-f0-9]{64}\\.(jpg|png|webp)")
        private val ALLOWED_MIME_TYPES = listOf("image/jpeg", "image/png", "image/webp")

        @JvmStatic
        fun validatePhoto(photo: StoredPhoto) {
            if (photo.sha256 == null || !SHA256_PATTERN.matches(photo.sha256) ||
                photo.key == null || !OBJECT_KEY_PATTERN.matches(photo.key) ||
                !photo.key.startsWith("apms/photos/" + photo.sha256 + ".") ||
                photo.bytes <= 0 || photo.bytes > 10 * 1024 * 1024 ||
                photo.mime == null || !ALLOWED_MIME_TYPES.contains(photo.mime)
            ) {
                throw IllegalStateException("Invalid archived photo metadata")
            }
        }
    }
}
