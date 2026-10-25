package com.pawbridge.backend.community.internal.note.application

class NoteReadMutationRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note read mutation is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        INACTIVE,
        NOT_FOUND,
    }
}
