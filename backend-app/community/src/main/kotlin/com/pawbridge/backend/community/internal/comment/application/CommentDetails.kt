package com.pawbridge.backend.community.internal.comment.application

import java.time.LocalDateTime

@JvmRecord
data class CommentDetails(
    val commentId: Long,
    val postId: Long,
    val authorId: Long,
    val authorNickname: String?,
    val content: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
