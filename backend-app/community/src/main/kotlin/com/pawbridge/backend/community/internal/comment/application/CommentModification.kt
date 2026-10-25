package com.pawbridge.backend.community.internal.comment.application

interface CommentModification {
    fun update(commentId: Long, content: String?)
}
