package com.pawbridge.backend.community.internal.note.application

import java.time.Instant

@JvmRecord
data class BlockPage(
    val content: List<BlockView>,
    val totalElements: Long,
    val totalPages: Int,
    val number: Int,
) {

    @JvmRecord
    data class BlockView(
        val memberId: Long?,
        val nickname: String?,
        val createdAt: Instant?,
    )
}
