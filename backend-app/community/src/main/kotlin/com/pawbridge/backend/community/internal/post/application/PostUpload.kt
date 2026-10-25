package com.pawbridge.backend.community.internal.post.application

import java.io.IOException
import java.io.InputStream

@JvmRecord
data class PostUpload(
    val originalFilename: String?,
    val contentType: String?,
    val size: Long,
    val content: Content,
) {
    fun interface Content {
        /** Return a fresh stream positioned at the beginning for each SDK read. */
        @Throws(IOException::class)
        fun open(): InputStream
    }
}
