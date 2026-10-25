package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException

class ReadPost(
    private val postReader: PostReader,
    private val userNicknameQuery: UserNicknameQuery,
) {

    fun read(postId: Long): PostDetails {
        val post = postReader.findById(postId)
            .orElseThrow { PostNotFoundException() }
        return details(post)
    }

    // Package-private in Java; internal here because Kotlin has no package-private visibility.
    internal fun details(post: PostSnapshot): PostDetails {
        val nickname = findAuthorNickname(post.authorId())
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
            val nickname = userNicknameQuery.findNickname(authorId)
                .orElseGet { missingNickname(authorId) }
            // The legacy HTTP decoder represents a zero-byte String body as null.
            if (nickname.isEmpty()) null else nickname
        } catch (unavailable: UserNicknameUnavailableException) {
            missingNickname(authorId)
        }

    private fun missingNickname(authorId: Long): String = "사용자" + authorId
}
