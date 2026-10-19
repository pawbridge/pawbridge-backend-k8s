package com.pawbridge.backend.community.internal.comment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class CreateCommentTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 30, 10, 123456789);
    private static final String CONTENT = "  댓글 본문\n ";
    private final PostReader posts = mock(PostReader.class);
    private final UserNicknameQuery nicknames = mock(UserNicknameQuery.class);
    private final CommentWriter writer = mock(CommentWriter.class);
    private final CreateComment create = new CreateComment(posts, nicknames, writer);

    @ParameterizedTest
    @ValueSource(strings = {"PROTECTION", "ADOPTION", "COMMUNICATION", "MISSING", "REPORT"})
    void given__another_authors_post_in_any_board__when__create__then__parent_nickname_and_committed_comment_determine_response(String board) {
        when(posts.findById(42L)).thenReturn(Optional.of(post(board)));
        when(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"));
        when(writer.create(42L, 7L, CONTENT)).thenReturn(saved());

        assertEquals(new CommentDetails(13L, 42L, 7L, "작성자", CONTENT, NOW, NOW),
                create.create(42L, 7L, CONTENT));
        InOrder order = inOrder(posts, nicknames, writer);
        order.verify(posts).findById(42L);
        order.verify(nicknames).findNickname(7L);
        order.verify(writer).create(42L, 7L, CONTENT);
        order.verifyNoMoreInteractions();
    }

    @Test
    void given__absent_or_deleted_parent__when__create__then__not_found_precedes_nickname_and_write() {
        when(posts.findById(42L)).thenReturn(Optional.empty());

        assertThrows(PostNotFoundException.class, () -> create.create(42L, 7L, CONTENT));
        verifyNoInteractions(nicknames, writer);
    }

    @Test
    void given__parent_read_failure__when__create__then__original_failure_prevents_nickname_and_write() {
        IllegalStateException failure = new IllegalStateException("parent read failed");
        when(posts.findById(42L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> create.create(42L, 7L, CONTENT)));
        verifyNoInteractions(nicknames, writer);
    }

    @ParameterizedTest
    @MethodSource("nicknameOutcomes")
    void given__defined_nickname_outcome__when__create__then__comment_is_written_with_existing_response_policy(
            UserNicknameQuery nickname, String expected) {
        when(posts.findById(42L)).thenReturn(Optional.of(post("COMMUNICATION")));
        when(writer.create(42L, 7L, CONTENT)).thenReturn(saved());

        assertEquals(new CommentDetails(13L, 42L, 7L, expected, CONTENT, NOW, NOW),
                new CreateComment(posts, nickname, writer).create(42L, 7L, CONTENT));
        InOrder order = inOrder(posts, writer);
        order.verify(posts).findById(42L);
        order.verify(writer).create(42L, 7L, CONTENT);
        order.verifyNoMoreInteractions();
    }

    @Test
    void given__unexpected_nickname_failure__when__create__then__original_failure_prevents_write() {
        when(posts.findById(42L)).thenReturn(Optional.of(post("COMMUNICATION")));
        IllegalArgumentException failure = new IllegalArgumentException("programming failure");
        when(nicknames.findNickname(7L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> create.create(42L, 7L, CONTENT)));
        verifyNoInteractions(writer);
    }

    @Test
    void given__writer_commit_failure__when__create__then__failure_propagates_without_repeating_reads_or_write() {
        when(posts.findById(42L)).thenReturn(Optional.of(post("COMMUNICATION")));
        when(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"));
        IllegalStateException failure = new IllegalStateException("commit outcome unknown");
        when(writer.create(42L, 7L, CONTENT)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> create.create(42L, 7L, CONTENT)));
        InOrder order = inOrder(posts, nicknames, writer);
        order.verify(posts).findById(42L);
        order.verify(nicknames).findNickname(7L);
        order.verify(writer).create(42L, 7L, CONTENT);
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

    private PostSnapshot post(String board) {
        return new PostSnapshot(42L, 99L, "다른 작성자의 게시글", "원문", board, List.of(), NOW, NOW);
    }

    private CommentSnapshot saved() {
        return new CommentSnapshot(13L, 42L, 7L, CONTENT, NOW, NOW);
    }
}
