package com.pawbridge.backend.community.internal.note.application

class NoteFavoriteRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note favorite mutation is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
        NOT_FOUND,
    }
}
