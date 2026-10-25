package com.pawbridge.backend.community.internal.post.application

/** Recheck ownership and commit post, event and projection together; null fields retain the locked row's values. */
fun interface PostModification {
    fun update(
        postId: Long,
        authorId: Long,
        title: String?,
        content: String?,
        imageUrls: List<String?>?,
    ): PostSnapshot
}
