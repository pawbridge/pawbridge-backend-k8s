package com.pawbridge.backend.community.internal.comment.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class CommentOwnershipTest {
    @Test
    fun `작성자가 같을 때__소유권을 확인하면__허용한다`() {
        // given
        val storedAuthorId = 7L
        val requesterId = 7L

        // when
        val check = Executable { CommentOwnership.requireAuthor(storedAuthorId, requesterId) }

        // then
        assertDoesNotThrow(check)
    }

    @Test
    fun `작성자가 다를 때__소유권을 확인하면__댓글 권한 메시지로 거부한다`() {
        // given
        val storedAuthorId = 7L
        val requesterId = 8L

        // when
        val failure = assertThrows(CommentNotOwnedException::class.java) {
            CommentOwnership.requireAuthor(storedAuthorId, requesterId)
        }

        // then
        assertEquals("댓글에 접근할 권한이 없습니다.", failure.message)
    }
}
