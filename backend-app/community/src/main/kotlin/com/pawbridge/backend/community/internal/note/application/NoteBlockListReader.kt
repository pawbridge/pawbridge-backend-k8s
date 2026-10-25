package com.pawbridge.backend.community.internal.note.application

import java.time.Instant

interface NoteBlockListReader {
    fun count(currentUserId: Long): Long

    fun read(
        currentUserId: Long,
        page: Int,
    ): List<Block>

    @JvmRecord
    data class Block(
        val memberId: Long,
        val createdAt: Instant?,
    )
}
