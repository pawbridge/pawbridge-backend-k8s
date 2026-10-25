package com.pawbridge.backend.community.internal.comment.domain

object CommentOwnership {

    @JvmStatic
    fun requireAuthor(storedAuthorId: Long, requestingAuthorId: Long) {
        if (storedAuthorId != requestingAuthorId) {
            throw CommentNotOwnedException()
        }
    }
}
