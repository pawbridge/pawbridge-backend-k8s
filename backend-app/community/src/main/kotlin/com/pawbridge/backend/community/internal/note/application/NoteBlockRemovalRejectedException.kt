package com.pawbridge.backend.community.internal.note.application

class NoteBlockRemovalRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note block removal is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
    }
}
