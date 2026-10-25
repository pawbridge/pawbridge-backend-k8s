package com.pawbridge.backend.community.internal.comment.application

/** Verifies active state and ownership, then soft-deletes the comment in one transaction. */
interface CommentDeletion {
    fun delete(commentId: Long, authorId: Long)
}
