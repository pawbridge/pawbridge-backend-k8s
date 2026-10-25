package com.pawbridge.backend.community.internal.note.application

class PrivateNoteRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note request is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
        NOT_FOUND,
    }
}
