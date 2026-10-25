package com.pawbridge.backend.community.internal.note.application

class NoteSendRejectedException(
    private val reason: Reason,
) : RuntimeException() {

    enum class Reason {
        LOGIN,
        INVALID_REQUEST,
        INVALID_BODY,
        INVALID_CONTEXT,
        INACTIVE,
        GONE,
        CONFLICT,
        BLOCKED,
        RATE_LIMIT,
        NOT_FOUND,
        REPLY_MISMATCH,
        CONTEXT_AUTHOR,
    }

    fun reason(): Reason = reason
}
