package com.pawbridge.backend.community.internal.comment.application

interface CommentWriter {
    fun create(postId: Long, authorId: Long, content: String?): CommentSnapshot
}
