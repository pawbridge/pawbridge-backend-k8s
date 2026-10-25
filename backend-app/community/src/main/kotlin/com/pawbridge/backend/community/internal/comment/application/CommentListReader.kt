package com.pawbridge.backend.community.internal.comment.application

interface CommentListReader {
    fun findByPostId(postId: Long): List<CommentSnapshot>
}
