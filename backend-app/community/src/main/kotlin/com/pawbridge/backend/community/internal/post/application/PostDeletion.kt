package com.pawbridge.backend.community.internal.post.application

/** Rechecks active state and ownership while atomically deleting the post's DB projections. */
interface PostDeletion {
    fun delete(postId: Long, authorId: Long)
}
