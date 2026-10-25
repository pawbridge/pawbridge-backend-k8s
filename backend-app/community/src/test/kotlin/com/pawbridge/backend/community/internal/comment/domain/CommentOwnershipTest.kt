package com.pawbridge.backend.community.internal.comment.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class CommentOwnershipTest {
    @Test
    fun given__same_author__when__checking_ownership__then__allow() {
        assertDoesNotThrow(Executable { CommentOwnership.requireAuthor(7L, 7L) })
    }

    @Test
    fun given__different_author__when__checking_ownership__then__reject_with_comment_message() {
        val failure = assertThrows(CommentNotOwnedException::class.java) {
            CommentOwnership.requireAuthor(7L, 8L)
        }
        assertEquals("댓글에 접근할 권한이 없습니다.", failure.message)
    }
}
