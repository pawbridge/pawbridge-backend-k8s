package com.pawbridge.backend.community.internal.comment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ListCommentsTest {
    private final CommentListReader commentListReader = mock(CommentListReader.class);
    private final UserNicknameQuery nicknameQuery = mock(UserNicknameQuery.class);
    private final ListComments listComments = new ListComments(commentListReader, nicknameQuery);

    @Test
    void given__no_comments__when__read__then__empty_list_without_nickname_lookup() {
        when(commentListReader.findByPostId(42L)).thenReturn(List.of());

        assertEquals(List.of(), listComments.read(42L));
        verifyNoInteractions(nicknameQuery);
    }

    @Test
    void given__ordered_comments_with_repeated_author__when__read__then__each_row_is_enriched_in_order() {
        CommentSnapshot first = comment(13L, 7L);
        CommentSnapshot second = comment(15L, 9L);
        CommentSnapshot third = comment(12L, 7L);
        when(commentListReader.findByPostId(42L)).thenReturn(List.of(first, second, third));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.of("첫 닉네임")).thenReturn(Optional.of("다음 닉네임"));
        when(nicknameQuery.findNickname(9L)).thenReturn(Optional.of("다른 작성자"));

        assertEquals(List.of(
                details(first, "첫 닉네임"), details(second, "다른 작성자"), details(third, "다음 닉네임")),
                listComments.read(42L));
        InOrder order = inOrder(commentListReader, nicknameQuery);
        order.verify(commentListReader).findByPostId(42L);
        order.verify(nicknameQuery).findNickname(7L);
        order.verify(nicknameQuery).findNickname(9L);
        order.verify(nicknameQuery).findNickname(7L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void given__missing_and_unavailable_nicknames__when__read__then__only_each_affected_row_uses_fallback() {
        CommentSnapshot missing = comment(13L, 7L);
        CommentSnapshot unavailable = comment(12L, 8L);
        CommentSnapshot available = comment(11L, 7L);
        when(commentListReader.findByPostId(42L)).thenReturn(List.of(missing, unavailable, available));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.empty()).thenReturn(Optional.of("회복한 닉네임"));
        when(nicknameQuery.findNickname(8L))
                .thenThrow(new UserNicknameUnavailableException(new IllegalStateException("unavailable")));

        assertEquals(List.of(
                details(missing, "사용자7"), details(unavailable, "사용자8"), details(available, "회복한 닉네임")),
                listComments.read(42L));
    }

    @Test
    void given__empty_and_whitespace_nicknames__when__read__then__empty_is_null_and_whitespace_is_preserved() {
        CommentSnapshot empty = comment(13L, 7L);
        CommentSnapshot whitespace = comment(12L, 8L);
        when(commentListReader.findByPostId(42L)).thenReturn(List.of(empty, whitespace));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.of(""));
        when(nicknameQuery.findNickname(8L)).thenReturn(Optional.of("  \t "));

        assertEquals(List.of(details(empty, null), details(whitespace, "  \t ")), listComments.read(42L));
    }

    @Test
    void given__comment_read_failure__when__read__then__original_failure_prevents_nickname_lookup() {
        IllegalStateException failure = new IllegalStateException("comment mapping failed");
        when(commentListReader.findByPostId(42L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> listComments.read(42L)));
        verifyNoInteractions(nicknameQuery);
    }

    @Test
    void given__unexpected_nickname_failure__when__read__then__whole_list_fails_and_later_rows_are_not_queried() {
        when(commentListReader.findByPostId(42L))
                .thenReturn(List.of(comment(13L, 7L), comment(12L, 8L), comment(11L, 9L)));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.of("정상"));
        IllegalArgumentException failure = new IllegalArgumentException("programming failure");
        when(nicknameQuery.findNickname(8L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> listComments.read(42L)));
        InOrder order = inOrder(nicknameQuery);
        order.verify(nicknameQuery).findNickname(7L);
        order.verify(nicknameQuery).findNickname(8L);
        order.verifyNoMoreInteractions();
    }

    private CommentDetails details(CommentSnapshot comment, String nickname) {
        return new CommentDetails(
                comment.commentId(), comment.postId(), comment.authorId(), nickname, comment.content(),
                comment.createdAt(), comment.updatedAt());
    }

    private CommentSnapshot comment(long commentId, long authorId) {
        return new CommentSnapshot(
                commentId, 42L, authorId, "댓글 본문 " + commentId,
                LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000),
                LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000));
    }
}
