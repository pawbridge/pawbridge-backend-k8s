package com.pawbridge.backend.community.internal.note.application

class NoteNotificationsRejectedException(
    private val reason: Reason,
) : RuntimeException("Note notifications request is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
    }
}
