package com.pawbridge.backend.community.internal.note.application

@JvmRecord
data class BlockedAuthors(
    val memberIds: List<Long>,
)
