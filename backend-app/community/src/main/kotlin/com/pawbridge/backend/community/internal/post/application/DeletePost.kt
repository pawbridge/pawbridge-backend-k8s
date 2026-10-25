package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostOwnership

class DeletePost(
    private val reader: PostReader,
    private val media: PostMediaRemover,
    private val deletion: PostDeletion,
) {

    fun delete(postId: Long, authorId: Long) {
        val post = reader.findById(postId).orElseThrow { PostNotFoundException() }
        PostOwnership.requireAuthor(post.authorId(), authorId)
        delete(post)
    }

    /** The separately authorized admin route deliberately has no author ownership restriction. */
    fun deleteAsAdmin(postId: Long) {
        delete(reader.findById(postId).orElseThrow { PostNotFoundException() })
    }

    private fun delete(post: PostSnapshot) {
        // The reader transaction has returned its connection before external I/O begins.
        media.remove(post.imageUrls())
        // Media is best effort. A later DB rollback or unknown commit never restores or retries it.
        // Retain the actual post author in the existing locking mutation; no special caller ID bypass is used.
        deletion.delete(post.postId(), post.authorId())
    }
}
