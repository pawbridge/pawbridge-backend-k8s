package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.UUID

interface NoteNotificationReader {
    fun read(
        currentUserId: Long,
        cursor: UUID?,
        now: Instant,
    ): List<Row>

    fun unread(
        currentUserId: Long,
        now: Instant,
    ): Long

    @JvmRecord
    data class Row(
        val noteId: UUID?,
        val senderId: Long?,
        val createdAt: Instant?,
        val readAt: Instant?,
    )
}
