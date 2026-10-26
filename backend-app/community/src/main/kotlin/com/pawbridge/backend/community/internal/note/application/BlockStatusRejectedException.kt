package com.pawbridge.backend.community.internal.note.application

class BlockStatusRejectedException(
    private val reason: Reason,
) : RuntimeException("Note block status request is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        INVALID_MEMBERS,
        LOGIN,
        INACTIVE,
    }
}
