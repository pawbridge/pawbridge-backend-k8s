package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.community.internal.comment.domain.CommentOwnership
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class UpdateComment(
    private val comments: CommentReader,
    private val userNicknameQuery: UserNicknameQuery,
    private val modification: CommentModification,
) {

    fun update(commentId: Long, authorId: Long, content: String?): CommentDetails {
        val comment = comments.findById(commentId).orElseThrow { CommentNotFoundException() }
        CommentOwnership.requireAuthor(comment.authorId, authorId)
        // The active snapshot read has returned its connection before nickname lookup.
        val nickname = findAuthorNickname(comment.authorId)
        // The Java original dereferenced the stored content (`comment.content().equals(content)`).
        if (comment.content!! != content) {
            modification.update(comment.commentId, content)
        }
        // Legacy captures the response before dirty checking updates the stored audit time.
        return CommentDetails(
            comment.commentId,
            comment.postId,
            comment.authorId,
            nickname,
            content,
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
