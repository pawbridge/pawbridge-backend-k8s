package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat

@JvmRecord
data class ArchivedPhoto(
    val bytes: ByteArray,
    val sourceHash: String,
    val storedHash: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val recipe: String,
) {
    fun objectKey(): String {
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw PhotoArchiveFailure("OPTIMIZER_CONTRACT", true)
        }
        return "apms/photos/" + storedHash + "." + extension
    }

    // Like the Java record, the byte array is compared by reference (the data class default) and
    // toString never prints the photo content: Kotlin's generated toString would dump the whole array.
    override fun toString(): String {
        return "ArchivedPhoto[bytes=" + bytes +
            ", sourceHash=" + sourceHash +
            ", storedHash=" + storedHash +
            ", contentType=" + contentType +
            ", width=" + width +
            ", height=" + height +
            ", recipe=" + recipe + "]"
    }

    companion object {
        const val MAX_BYTES: Int = 10 * 1024 * 1024

        @JvmStatic
        fun sha256(bytes: ByteArray): String {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException(impossible)
            }
        }
    }
}
