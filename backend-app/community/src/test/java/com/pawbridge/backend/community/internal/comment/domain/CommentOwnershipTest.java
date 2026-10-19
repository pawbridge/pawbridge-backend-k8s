package com.pawbridge.backend.community.internal.comment.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommentOwnershipTest {
    @Test
    void given__same_author__when__checking_ownership__then__allow() {
        assertDoesNotThrow(() -> CommentOwnership.requireAuthor(7L, 7L));
    }

    @Test
    void given__different_author__when__checking_ownership__then__reject_with_comment_message() {
        CommentNotOwnedException failure = assertThrows(CommentNotOwnedException.class,
                () -> CommentOwnership.requireAuthor(7L, 8L));
        assertEquals("댓글에 접근할 권한이 없습니다.", failure.getMessage());
    }
}
