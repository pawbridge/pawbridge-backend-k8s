package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class ListPosts(
    private val postListReader: PostListReader,
    private val userNicknameQuery: UserNicknameQuery,
) {

    fun read(): List<PostDetails> {
        val posts = postListReader.findAll()
        val details = ArrayList<PostDetails>(posts.size)
        for (post in posts) {
            val nickname = findAuthorNickname(post.authorId())
            details.add(
                PostDetails(
                    post.postId(),
                    post.authorId(),
                    nickname,
                    post.title(),
                    post.content(),
                    post.boardType(),
                    post.imageUrls(),
                    post.createdAt(),
                    post.updatedAt(),
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
