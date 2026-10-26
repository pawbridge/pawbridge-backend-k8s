package com.pawbridge.backend.community.internal.note.application

class PrivateNoteListRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note list request is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        INVALID_PAGE,
        INVALID_BOX,
        LOGIN,
        INACTIVE,
    }
}
