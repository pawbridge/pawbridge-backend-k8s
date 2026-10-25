package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class CreateComment(
    private val posts: PostReader,
    private val userNicknameQuery: UserNicknameQuery,
    private val writer: CommentWriter,
) {

    fun create(postId: Long, authorId: Long, content: String?): CommentDetails {
        posts.findById(postId).orElseThrow { PostNotFoundException() }
        val nickname = findAuthorNickname(authorId)
        // Both reads have returned before the short comment write transaction starts.
        val comment = writer.create(postId, authorId, content)
        return CommentDetails(
            comment.commentId,
            comment.postId,
            comment.authorId,
            nickname,
            comment.content,
            comment.createdAt,
            comment.updatedAt,
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
