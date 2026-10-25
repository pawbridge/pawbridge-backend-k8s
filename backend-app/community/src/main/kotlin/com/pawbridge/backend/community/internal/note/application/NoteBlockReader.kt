package com.pawbridge.backend.community.internal.note.application

interface NoteBlockReader {
    fun blocked(
        firstMemberId: Long,
        secondMemberId: Long,
    ): Boolean
}
