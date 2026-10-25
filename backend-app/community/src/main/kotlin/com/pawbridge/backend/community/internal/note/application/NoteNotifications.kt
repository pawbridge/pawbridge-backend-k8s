package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.UUID

@JvmRecord
data class NoteNotifications(
    val content: List<Notification>,
    val nextCursor: UUID?,
    val unreadCount: Long,
) {

    @JvmRecord
    data class Notification(
        val noteId: UUID?,
        val kind: String?,
        val actorId: Long?,
        val actorNickname: String?,
        val createdAt: Instant?,
        val read: Boolean,
        val href: String?,
    )
}
