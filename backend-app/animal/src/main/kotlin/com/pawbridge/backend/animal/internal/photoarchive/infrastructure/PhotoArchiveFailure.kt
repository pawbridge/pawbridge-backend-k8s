package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

/** Stable codes only: do not put credentials, source URLs or response bodies in errors. */
class PhotoArchiveFailure(
    code: String,
    private val slowRetry: Boolean,
) : RuntimeException(code) {

    fun slowRetry(): Boolean = slowRetry
}
