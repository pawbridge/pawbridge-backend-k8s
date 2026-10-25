package com.pawbridge.backend.community.internal.note.application

class NoteBlockListRejectedException(
    private val reason: Reason,
) : RuntimeException("Note block list request is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        INVALID_PAGE,
        LOGIN,
        INACTIVE,
    }
}
