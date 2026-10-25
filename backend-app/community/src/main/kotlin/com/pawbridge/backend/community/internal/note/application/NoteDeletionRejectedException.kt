package com.pawbridge.backend.community.internal.note.application

class NoteDeletionRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note deletion is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
        NOT_FOUND,
    }
}
