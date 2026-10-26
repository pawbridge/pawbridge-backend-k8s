package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.Optional
import java.util.UUID

interface PrivateNoteReader {
    fun find(
        currentUserId: Long,
        noteId: UUID?,
        now: Instant,
    ): Optional<StoredNote>

    @JvmRecord
    data class StoredNote(
        val noteId: UUID?,
        val senderId: Long?,
        val recipientId: Long?,
        val body: String?,
        val contextType: String?,
        val contextId: Long?,
        val createdAt: Instant?,
        val direction: String?,
        val readAt: Instant?,
        val favorite: Boolean,
    )
}
