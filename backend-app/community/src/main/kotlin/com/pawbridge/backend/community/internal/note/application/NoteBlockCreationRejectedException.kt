package com.pawbridge.backend.community.internal.note.application

class NoteBlockCreationRejectedException(
    private val reason: Reason,
) : RuntimeException("Private note block creation is not allowed.") {

    fun reason(): Reason = reason

    enum class Reason {
        LOGIN,
        SELF,
        INACTIVE,
    }
}
