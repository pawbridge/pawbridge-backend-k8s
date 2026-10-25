package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.UUID

@JvmRecord
data class PrivateNoteView(
    val noteId: UUID?,
    val body: String?,
    val direction: String?,
    val createdAt: Instant?,
    val readAt: Instant?,
    val favorite: Boolean,
    val counterpartId: Long?,
    val counterpartNickname: String?,
    val canReply: Boolean,
    val contextHref: String?,
)
