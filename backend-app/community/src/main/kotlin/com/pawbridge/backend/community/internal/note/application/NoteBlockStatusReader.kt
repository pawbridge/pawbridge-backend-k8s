package com.pawbridge.backend.community.internal.note.application

interface NoteBlockStatusReader {
    fun blockedAuthors(
        currentUserId: Long,
        distinctMemberIds: List<Long>,
    ): List<Long>
}
