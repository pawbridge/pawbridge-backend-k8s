package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class CreatePost(
    private val mediaUploader: PostMediaUploader,
    private val userNicknameQuery: UserNicknameQuery,
    private val writer: PostWriter,
) {

    fun create(
        authorId: Long,
        title: String?,
        content: String?,
        boardType: PostBoardType,
        files: List<PostUpload>?,
    ): PostDetails {
        boardType.requireGeneralPost()
        val imageUrls = mediaUploader.upload(files)
        val nickname = findAuthorNickname(authorId)
        // Each adapter owns its short transaction; uploads never hold a DB connection.
        val post = writer.create(PostToCreate(authorId, title, content, boardType, imageUrls))
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

    private fun findAuthorNickname(authorId: Long): String? =
        try {
            val nickname = userNicknameQuery.findNickname(authorId).orElse("사용자" + authorId)
            if (nickname.isEmpty()) null else nickname
        } catch (unavailable: UserNicknameUnavailableException) {
            "사용자" + authorId
        }
}
