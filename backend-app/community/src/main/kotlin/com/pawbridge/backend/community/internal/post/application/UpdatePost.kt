package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostOwnership
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class UpdatePost(
    private val reader: PostReader,
    private val remover: PostMediaRemover,
    private val uploader: PostMediaUploader,
    private val users: UserNicknameQuery,
    private val modification: PostModification,
) {

    fun update(
        postId: Long,
        authorId: Long,
        title: String?,
        content: String?,
        files: List<PostUpload>,
    ): PostDetails {
        val previous = reader.findById(postId).orElseThrow { PostNotFoundException() }
        PostOwnership.requireAuthor(previous.authorId(), authorId)
        return update(previous, title, content, files)
    }

    /** The separately authorized admin route changes the post without an author ownership check. */
    fun updateAsAdmin(postId: Long, title: String?, content: String?, files: List<PostUpload>): PostDetails {
        val previous = reader.findById(postId).orElseThrow { PostNotFoundException() }
        return update(previous, title, content, files)
    }

    private fun update(previous: PostSnapshot, title: String?, content: String?, files: List<PostUpload>): PostDetails {
        var imageUrls: List<String>? = null
        // One empty multipart file still requests replacement; the uploader then returns an empty list.
        if (!files.isEmpty()) {
            remover.remove(previous.imageUrls())
            imageUrls = uploader.upload(files)
        }
        val nickname = nickname(previous.authorId())
        // Reader, media and nickname I/O have finished before the short locking writer transaction.
        // The stored author is post data, never the admin caller's identity. The writer still locks and rechecks it.
        val post = modification.update(previous.postId(), previous.authorId(), title, content, imageUrls)
        return PostDetails(
            post.postId(),
            post.authorId(),
            nickname,
            post.title(),
            post.content(),
            post.boardType(),
            post.imageUrls(),
            post.createdAt(),
            post.updatedAt(),
        )
    }

    private fun nickname(authorId: Long): String? =
        try {
            val value = users.findNickname(authorId).orElse("사용자" + authorId)
            if (value.isEmpty()) null else value
        } catch (unavailable: UserNicknameUnavailableException) {
            "사용자" + authorId
        }
}
