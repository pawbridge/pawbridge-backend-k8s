package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class ListComments(
    private val commentListReader: CommentListReader,
    private val userNicknameQuery: UserNicknameQuery,
) {

    fun read(postId: Long): List<CommentDetails> {
        val comments = commentListReader.findByPostId(postId)
        val details = ArrayList<CommentDetails>(comments.size)
        for (comment in comments) {
            val nickname = findAuthorNickname(comment.authorId)
            details.add(
                CommentDetails(
                    comment.commentId,
                    comment.postId,
                    comment.authorId,
                    nickname,
                    comment.content,
                    comment.createdAt,
                    comment.updatedAt,
                ),
            )
        }
        return details
    }

    private fun findAuthorNickname(authorId: Long): String? =
        try {
            val nickname = userNicknameQuery.findNickname(authorId)
                .orElseGet { missingNickname(authorId) }
            // The legacy HTTP decoder represents a zero-byte String body as null.
            if (nickname.isEmpty()) null else nickname
        } catch (unavailable: UserNicknameUnavailableException) {
            missingNickname(authorId)
        }

    private fun missingNickname(authorId: Long): String = "사용자" + authorId
}
