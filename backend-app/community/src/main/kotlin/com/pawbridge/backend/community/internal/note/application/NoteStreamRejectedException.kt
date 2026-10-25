package com.pawbridge.backend.community.internal.note.application

class NoteStreamRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note stream is not available.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
    }
}
