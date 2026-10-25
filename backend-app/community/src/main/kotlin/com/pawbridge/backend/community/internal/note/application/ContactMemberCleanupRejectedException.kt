package com.pawbridge.backend.community.internal.note.application

class ContactMemberCleanupRejectedException(
    private val reason: Reason,
) : RuntimeException() {

    enum class Reason {
        LOGIN,
        NOT_PENDING,
    }

    fun reason(): Reason = reason
}
