package com.pawbridge.backend.animal.internal.gallery.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.function.Consumer
import java.util.function.Function
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Immutable, bounded disk snapshots; only the requested page is signed in memory. */
class LostGallerySnapshots @Throws(IOException::class) constructor(
    private val mapper: ObjectMapper,
    private val source: Consumer<Consumer<Entry>>,
    private val signer: Function<String, String>,
    private val root: Path,
    private val clock: Clock,
) : AutoCloseable {

    // The nested types below are package-private in Java; Kotlin has no package-private, so they are public here.
    @JvmRecord
    data class Entry(
        val record: Map<String, Any?>,
        val photo: LostGalleryFeed.StoredPhoto,
    )

    @JvmRecord
    data class Descriptor(
        val protocol: String,
        val snapshotId: String,
        val snapshotSha256: String,
        val count: Int,
        val cursor: String,
        val expiresAt: Long,
    )

    @JvmRecord
    data class Page(
        val protocol: String,
        val snapshotId: String,
        val snapshotSha256: String,
        val total: Int,
        val start: Int,
        val items: List<WireEntry>,
        val nextCursor: String?,
        val complete: Boolean,
    )

    @JvmRecord
    data class WirePhoto(
        val sha256: String?,
        val key: String?,
        val bytes: Long,
        val mime: String?,
        val url: String?,
    )

    @JvmRecord
    data class WireEntry(
        val record: Map<String, Any?>,
        val photo: WirePhoto,
    )

    private class Snapshot(
        val descriptor: Descriptor,
        val file: Path,
        val requestId: String,
    )

    private class Position(
        val offset: Long,
        val count: Int,
    )

    class Gone : RuntimeException()

    class BadCursor : RuntimeException()

    private val snapshots = LinkedHashMap<String, Snapshot>()
    private val secret = ByteArray(32)
    private val ownerChannel: FileChannel
    private val owner: FileLock

    init {
        Files.createDirectories(root)
        if (Files.isSymbolicLink(root)) {
            throw IOException("Snapshot directory cannot be a symlink")
        }
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        } catch (ignored: UnsupportedOperationException) {
            // Local Windows contract tests.
        }
        ownerChannel = FileChannel.open(root.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired: FileLock? = try {
            ownerChannel.tryLock()
        } catch (error: Exception) {
            ownerChannel.close()
            throw error
        }
        if (acquired == null) {
            ownerChannel.close()
            throw IOException("Snapshot directory already owned")
        }
        owner = acquired
        SecureRandom().nextBytes(secret)
        // Exclusive ownership means these files can only be abandoned by a previous process.
        try {
            Files.list(root).use { paths ->
                for (path in paths.filter { it.fileName.toString().matches(ABANDONED_FILE) }.toList()) {
                    Files.delete(path)
                }
            }
        } catch (error: IOException) {
            owner.release()
            ownerChannel.close()
            throw error
        }
    }

    // Package-private in Java (used by the unit tests); public here because Kotlin has no package-private.
    @Throws(IOException::class)
    fun create(): Descriptor = create(UUID.randomUUID().toString().replace("-", ""))

    @Synchronized
    @Throws(IOException::class)
    fun create(requestId: String?): Descriptor {
        if (requestId == null || !SNAPSHOT_ID.matches(requestId)) {
            throw BadCursor()
        }
        expire()
        for (existing in snapshots.values) {
            if (existing.requestId == requestId) {
                return find(existing.descriptor.snapshotId).descriptor
            }
        }
        if (snapshots.size >= 2) {
            throw IllegalStateException("Snapshot retention limit reached")
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val partial = root.resolve("$id.partial")
        val complete = root.resolve("$id.snapshot")
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        var count = 0
        var size = 0L
        var last = 0L
        var chain = initialChain()
        try {
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW))).use { output ->
                source.accept(
                    Consumer { entry ->
                        validate(entry)
                        val current = (entry.record["id"] as Number).toLong()
                        if (current <= last || ++count > LostGalleryFeed.MAX_RECORDS || System.nanoTime() > deadline) {
                            throw IllegalStateException("Snapshot order, count or deadline exceeded")
                        }
                        val bytes = json(entry)
                        if (bytes.size > ROW_BYTES) {
                            throw IllegalStateException("Snapshot storage limit exceeded")
                        }
                        size += bytes.size + 4L
                        if (size > FILE_BYTES) {
                            throw IllegalStateException("Snapshot storage limit exceeded")
                        }
                        try {
                            output.writeInt(bytes.size)
                            output.write(bytes)
                        } catch (error: IOException) {
                            throw UncheckedIOException(error)
                        }
                        last = current
                        chain = advance(chain, entry)
                    },
                )
            }
            if (count == 0) {
                throw IllegalStateException("Empty snapshot is not publishable")
            }
            val sha = HexFormat.of().formatHex(chain)
            Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE)
            val descriptor = Descriptor(PROTOCOL, id, sha, count, cursor(id, 0, 0), clock.millis() + TTL.toMillis())
            snapshots[id] = Snapshot(descriptor, complete, requestId)
            return descriptor
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun describe(id: String?): Descriptor = find(id).descriptor

    @Synchronized
    @Throws(IOException::class)
    fun page(id: String?, cursor: String?, limit: Int): ByteArray {
        if (limit < 1 || limit > MAX_PAGE) {
            throw BadCursor()
        }
        val snapshot = find(id)
        val position = position(id, cursor)
        if (position.offset < 0 || position.offset >= Files.size(snapshot.file) ||
            position.count < 0 || position.count >= snapshot.descriptor.count
        ) {
            throw BadCursor()
        }
        val items = ArrayList<WireEntry>()
        var used = 2048L
        var nextOffset = position.offset
        RandomAccessFile(snapshot.file.toFile(), "r").use { input ->
            input.seek(position.offset)
            while (items.size < limit && position.count + items.size < snapshot.descriptor.count) {
                val start = input.filePointer
                val length = input.readInt()
                if (length < 1 || length > ROW_BYTES) {
                    throw IOException("Invalid snapshot row size")
                }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                val entry = mapper.readValue(bytes, Entry::class.java)
                val photo = entry.photo
                val url = signer.apply(photo.key!!)
                if (url.length > 8192) {
                    throw IOException("Photo URL exceeds limit")
                }
                val item = WireEntry(
                    entry.record,
                    WirePhoto(photo.sha256, photo.key, photo.bytes, photo.mime, url),
                )
                val encoded = json(item).size + 1
                if (used + encoded > PAGE_BYTES) {
                    if (items.isEmpty()) {
                        throw IOException("Single item exceeds page limit")
                    }
                    nextOffset = start
                    break
                }
                used += encoded
                items.add(item)
                nextOffset = input.filePointer
            }
        }
        val nextCount = position.count + items.size
        val done = nextCount == snapshot.descriptor.count
        val body = json(
            Page(
                PROTOCOL,
                id!!,
                snapshot.descriptor.snapshotSha256,
                snapshot.descriptor.count,
                position.count,
                items,
                if (done) null else cursor(id, nextOffset, nextCount),
                done,
            ),
        )
        if (body.size > PAGE_BYTES) {
            throw IOException("Page exceeds transfer limit")
        }
        return body
    }

    @Synchronized
    @Throws(IOException::class)
    fun release(id: String?) {
        val value = find(id)
        Files.deleteIfExists(value.file)
        snapshots.remove(id)
    }

    private fun find(id: String?): Snapshot {
        expire()
        if (id == null || !SNAPSHOT_ID.matches(id) || !snapshots.containsKey(id)) {
            throw Gone()
        }
        val previous = snapshots[id]!!
        val value = previous.descriptor
        val renewed = Snapshot(
            Descriptor(
                value.protocol,
                value.snapshotId,
                value.snapshotSha256,
                value.count,
                value.cursor,
                clock.millis() + TTL.toMillis(),
            ),
            previous.file,
            previous.requestId,
        )
        snapshots[id] = renewed
        return renewed
    }

    private fun expire() {
        val iterator = snapshots.values.iterator()
        while (iterator.hasNext()) {
            val snapshot = iterator.next()
            if (snapshot.descriptor.expiresAt <= clock.millis()) {
                Files.deleteIfExists(snapshot.file)
                iterator.remove()
            }
        }
    }

    private fun cursor(id: String, offset: Long, count: Int): String {
        val value = "$id:$offset:$count"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8)) +
            "." + HexFormat.of().formatHex(mac(value))
    }

    private fun position(id: String?, token: String?): Position {
        try {
            if (token == null || token.length > 256) {
                throw BadCursor()
            }
            // Java's split("\\.", -1) and Kotlin's split(".") both keep trailing empty strings.
            val parts = token.split(".")
            if (parts.size != 2) {
                throw BadCursor()
            }
            val value = String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
            if (!MessageDigest.isEqual(mac(value), HexFormat.of().parseHex(parts[1]))) {
                throw BadCursor()
            }
            val fields = value.split(":")
            if (fields.size != 3 || fields[0] != id) {
                throw BadCursor()
            }
            return Position(java.lang.Long.parseLong(fields[1]), Integer.parseInt(fields[2]))
        } catch (error: IllegalArgumentException) {
            throw BadCursor()
        }
    }

    private fun mac(value: String): ByteArray {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Cursor signing unavailable")
        }
    }

    private fun json(value: Any): ByteArray {
        try {
            return mapper.writeValueAsBytes(value)
        } catch (error: IOException) {
            throw UncheckedIOException(error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        try {
            for (value in snapshots.values) {
                Files.deleteIfExists(value.file)
            }
            snapshots.clear()
        } finally {
            owner.release()
            ownerChannel.close()
        }
    }

    companion object {
        // Package-private in Java; public here because Kotlin has no package-private (tests and the feed use them).
        const val PROTOCOL: String = "pawbridge-gallery-pages-v2"
        const val MAX_PAGE: Int = 500
        const val PAGE_BYTES: Int = 2 * 1024 * 1024
        const val ROW_BYTES: Int = 256 * 1024
        const val FILE_BYTES: Long = 128L * 1024 * 1024

        @JvmField
        val TTL: Duration = Duration.ofHours(6)

        @JvmField
        val METADATA: List<String> =
            listOf("status", "happen_date", "happen_place", "color", "special_mark", "description")

        private val SNAPSHOT_ID = Regex("[a-f0-9]{32}")
        private val ABANDONED_FILE = Regex("[a-f0-9]{32}\\.(partial|snapshot)")

        @JvmStatic
        fun initialChain(): ByteArray = sha((PROTOCOL + "\n").toByteArray(StandardCharsets.UTF_8))

        @JvmStatic
        fun advance(chain: ByteArray, entry: Entry): ByteArray {
            val row = digest()
            val values = ArrayList<Any?>()
            values.add(entry.record["id"])
            values.add(entry.record["species"])
            values.add(entry.record["source_sha256"])
            for (field in METADATA) {
                values.add(entry.record[field])
            }
            values.add(entry.photo.key)
            values.add(entry.photo.bytes)
            values.add(entry.photo.mime)
            for (value in values) {
                val bytes = value?.toString()?.toByteArray(StandardCharsets.UTF_8)
                row.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
                if (bytes != null) {
                    row.update(bytes)
                }
            }
            val result = digest()
            result.update(chain)
            return result.digest(row.digest())
        }

        private fun sha(value: ByteArray): ByteArray = digest().digest(value)

        private fun digest(): MessageDigest {
            try {
                return MessageDigest.getInstance("SHA-256")
            } catch (error: NoSuchAlgorithmException) {
                throw IllegalStateException(error)
            }
        }

        @JvmStatic
        fun validate(entry: Entry) {
            LostGalleryFeed.validatePhoto(entry.photo)
            val id = entry.record["id"]
            val species = entry.record["species"]
            if (!(id is Long || id is Int) || (id as Number).toLong() <= 0 ||
                !("DOG" == species || "CAT" == species) ||
                entry.photo.sha256 != entry.record["source_sha256"]
            ) {
                throw IllegalStateException("Invalid gallery record")
            }
            for (field in METADATA) {
                val value = entry.record[field]
                if (value != null && (value !is String || value.length > 10000)) {
                    throw IllegalStateException("Invalid gallery metadata")
                }
            }
            try {
                AnimalStatus.valueOf(entry.record["status"] as String)
            } catch (error: RuntimeException) {
                throw IllegalStateException("Invalid gallery status")
            }
        }
    }
}
