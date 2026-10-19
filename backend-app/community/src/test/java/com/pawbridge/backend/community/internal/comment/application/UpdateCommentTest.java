package com.pawbridge.backend.community.internal.comment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class UpdateCommentTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 18, 1, 2, 3, 123456789);
    private static final LocalDateTime UPDATED = CREATED.plusDays(1);
    private static final String ORIGINAL = "기존 댓글";
    private static final String CONTENT = "  수정 원문\n\u2003 ";
    private final CommentReader comments = mock(CommentReader.class);
    private final UserNicknameQuery nicknames = mock(UserNicknameQuery.class);
    private final CommentModification modification = mock(CommentModification.class);
    private final UpdateComment update = new UpdateComment(comments, nicknames, modification);

    @Test
    void given__owned_comment_and_changed_content__when__update__then__read_nickname_and_committed_write_keep_original_response_times() {
        when(comments.findById(13L)).thenReturn(Optional.of(snapshot()));
        when(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"));

        assertEquals(new CommentDetails(13L, 42L, 7L, "작성자", CONTENT, CREATED, UPDATED),
                update.update(13L, 7L, CONTENT));
        InOrder order = inOrder(comments, nicknames, modification);
        order.verify(comments).findById(13L);
        order.verify(nicknames).findNickname(7L);
        order.verify(modification).update(13L, CONTENT);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__content_changed_after_snapshot__when__update__then__initial_content_alone_decides_whether_to_write(boolean changed) {
        when(comments.findById(13L)).thenReturn(Optional.of(snapshot()));
        AtomicReference<String> stored = new AtomicReference<>(ORIGINAL);
        AtomicInteger nicknameReads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        UserNicknameQuery nickname = authorId -> {
            assertEquals(7L, authorId);
            nicknameReads.incrementAndGet();
            stored.set(CONTENT);
            return Optional.of("작성자");
        };
        CommentModification modifying = (commentId, content) -> {
            assertEquals(13L, commentId);
            writes.incrementAndGet();
            stored.set(content);
        };
        String requested = changed ? CONTENT : ORIGINAL;

        assertEquals(new CommentDetails(13L, 42L, 7L, "작성자", requested, CREATED, UPDATED),
                new UpdateComment(comments, nickname, modifying).update(13L, 7L, requested));
        assertEquals(CONTENT, stored.get());
        assertEquals(1, nicknameReads.get());
        assertEquals(changed ? 1 : 0, writes.get());
    }

    @Test
    void given__absent_or_deleted_comment__when__update_by_another_author__then__not_found_precedes_nickname_and_write() {
        when(comments.findById(13L)).thenReturn(Optional.empty());

        CommentNotFoundException failure = assertThrows(CommentNotFoundException.class,
                () -> update.update(13L, 99L, CONTENT));
        assertEquals("댓글을 찾을 수 없습니다.", failure.getMessage());
        verifyNoInteractions(nicknames, modification);
    }

    @Test
    void given__another_authors_comment__when__update_with_same_content__then__ownership_precedes_nickname_and_write() {
        when(comments.findById(13L)).thenReturn(Optional.of(snapshot()));

        CommentNotOwnedException failure = assertThrows(CommentNotOwnedException.class,
                () -> update.update(13L, 99L, ORIGINAL));
        assertEquals("댓글에 접근할 권한이 없습니다.", failure.getMessage());
        verifyNoInteractions(nicknames, modification);
    }

    @Test
    void given__snapshot_read_failure__when__update__then__original_failure_prevents_nickname_and_write() {
        IllegalStateException failure = new IllegalStateException("read failed");
        when(comments.findById(13L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> update.update(13L, 7L, CONTENT)));
        verifyNoInteractions(nicknames, modification);
    }

    @ParameterizedTest
    @MethodSource("nicknameOutcomes")
    void given__defined_nickname_outcome__when__update__then__existing_fallback_and_raw_nickname_policy_are_preserved(
            UserNicknameQuery nickname, String expected) {
        when(comments.findById(13L)).thenReturn(Optional.of(snapshot()));

        assertEquals(new CommentDetails(13L, 42L, 7L, expected, CONTENT, CREATED, UPDATED),
                new UpdateComment(comments, nickname, modification).update(13L, 7L, CONTENT));
        InOrder order = inOrder(comments, modification);
        order.verify(comments).findById(13L);
        order.verify(modification).update(13L, CONTENT);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__unexpected_nickname_failure__when__updating_changed_or_same_content__then__original_failure_prevents_write(boolean changed) {
        when(comments.findById(13L)).thenReturn(Optional.of(snapshot()));
        IllegalArgumentException failure = new IllegalArgumentException("unexpected User failure");
        when(nicknames.findNickname(7L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> update.update(13L, 7L, changed ? CONTENT : ORIGINAL)));
        verifyNoInteractions(modification);
    }

    @Test
    void given__modification_commit_failure__when__update__then__failure_propagates_without_response_or_retry() {
        when(comments.findById(13L)).thenReturn(Optional.of(snapshot()));
        when(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"));
        IllegalStateException failure = new IllegalStateException("commit outcome unknown");
        doThrow(failure).when(modification).update(13L, CONTENT);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> update.update(13L, 7L, CONTENT)));
        InOrder order = inOrder(comments, nicknames, modification);
        order.verify(comments).findById(13L);
        order.verify(nicknames).findNickname(7L);
        order.verify(modification).update(13L, CONTENT);
        order.verifyNoMoreInteractions();
    }

    private static Stream<Arguments> nicknameOutcomes() {
        return Stream.of(
                Arguments.of((UserNicknameQuery) id -> Optional.of("작성자"), "작성자"),
                Arguments.of((UserNicknameQuery) id -> Optional.empty(), "사용자7"),
                Arguments.of((UserNicknameQuery) id -> {
                    throw new UserNicknameUnavailableException(new IllegalStateException("unavailable"));
                }, "사용자7"),
                Arguments.of((UserNicknameQuery) id -> Optional.of(""), null),
                Arguments.of((UserNicknameQuery) id -> Optional.of("  \t "), "  \t "));
    }

    private CommentSnapshot snapshot() {
        return new CommentSnapshot(13L, 42L, 7L, ORIGINAL, CREATED, UPDATED);
    }
}
