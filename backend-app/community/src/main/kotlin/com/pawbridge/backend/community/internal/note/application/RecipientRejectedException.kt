package com.pawbridge.backend.community.internal.note.application

class RecipientRejectedException(
    private val reason: Reason,
) : RuntimeException("Note recipient is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
        BLOCKED,
    }
}
