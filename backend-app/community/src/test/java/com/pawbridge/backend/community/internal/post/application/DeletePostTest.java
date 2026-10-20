package com.pawbridge.backend.community.internal.post.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeletePostTest {
    private final PostReader reader = mock(PostReader.class);
    private final PostMediaRemover media = mock(PostMediaRemover.class);
    private final PostDeletion deletion = mock(PostDeletion.class);
    private final DeletePost useCase = new DeletePost(reader, media, deletion);

    @Test
    void given__another_authors_post__when__deleting_as_admin__then__retain_its_author_in_the_same_locking_mutation() {
        PostSnapshot post = post(8L,"COMMUNICATION",List.of("first","first"));
        when(reader.findById(42L)).thenReturn(Optional.of(post));
        useCase.deleteAsAdmin(42L);
        var order=inOrder(reader,media,deletion);
        order.verify(reader).findById(42L);order.verify(media).remove(post.imageUrls());
        order.verify(deletion).delete(42L,8L);order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(PostBoardType.class)
    void given__own_active_post_of_any_board__when__delete__then__read_media_and_write_occur_once_in_order(PostBoardType board) {
        PostSnapshot post = post(7L, board.name(), List.of("https://fixture.invalid/posts/images/one.png"));
        when(reader.findById(42L)).thenReturn(Optional.of(post));

        useCase.delete(42L, 7L);

        var order = inOrder(reader, media, deletion);
        order.verify(reader).findById(42L);
        order.verify(media).remove(post.imageUrls());
        order.verify(deletion).delete(42L, 7L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void given__missing_or_already_deleted_post__when__delete__then__not_found_precedes_any_effect() {
        when(reader.findById(42L)).thenReturn(Optional.empty());
        assertThrows(PostNotFoundException.class, () -> useCase.delete(42L, 7L));
        verifyNoInteractions(media, deletion);
    }

    @Test
    void given__another_author__when__delete__then__fixed_forbidden_precedes_any_effect() {
        when(reader.findById(42L)).thenReturn(Optional.of(post(8L, "COMMUNICATION", List.of())));
        var failure = assertThrows(PostNotOwnedException.class, () -> useCase.delete(42L, 7L));
        assertEquals("게시글에 접근할 권한이 없습니다.", failure.getMessage());
        verifyNoInteractions(media, deletion);
    }

    @Test
    void given__reader_failure__when__delete__then__original_failure_precedes_any_effect() {
        var failure = new IllegalStateException("synthetic reader failure");
        when(reader.findById(42L)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.delete(42L, 7L)));
        verifyNoInteractions(media, deletion);
    }

    @Test
    void given__null_or_duplicate_media__when__delete__then__list_is_passed_without_normalization() {
        for (List<String> urls : Arrays.<List<String>>asList(null, List.of(), Arrays.asList("first", null, "first", "last"))) {
            reset(reader, media, deletion);
            PostSnapshot post = post(7L, "MISSING", urls);
            when(reader.findById(42L)).thenReturn(Optional.of(post));
            useCase.delete(42L, 7L);
            verify(media).remove(post.imageUrls());
            verify(deletion).delete(42L, 7L);
        }
    }

    @Test
    void given__late_not_found_after_media__when__delete__then__failure_propagates_without_retry_or_compensation() {
        when(reader.findById(42L)).thenReturn(Optional.of(post(7L, "REPORT", List.of("first"))));
        var failure = new PostNotFoundException();
        doThrow(failure).when(deletion).delete(42L, 7L);
        assertSame(failure, assertThrows(PostNotFoundException.class, () -> useCase.delete(42L, 7L)));
        verify(media).remove(List.of("first"));
        verify(deletion).delete(42L, 7L);
        verifyNoMoreInteractions(media, deletion);
    }

    @Test
    void given__late_ownership_rejection__when__delete__then__prior_media_attempt_is_not_repeated() {
        when(reader.findById(42L)).thenReturn(Optional.of(post(7L, "ADOPTION", List.of("first"))));
        doThrow(new PostNotOwnedException()).when(deletion).delete(42L, 7L);
        assertThrows(PostNotOwnedException.class, () -> useCase.delete(42L, 7L));
        verify(media).remove(List.of("first"));
        verify(deletion).delete(42L, 7L);
        verifyNoMoreInteractions(media, deletion);
    }

    @Test
    void given__commit_outcome_unknown__when__delete__then__original_failure_and_no_application_retry() {
        when(reader.findById(42L)).thenReturn(Optional.of(post(7L, "PROTECTION", List.of("first"))));
        var failure = new IllegalStateException("synthetic commit outcome unknown");
        doThrow(failure).when(deletion).delete(42L, 7L);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.delete(42L, 7L)));
        verify(reader).findById(42L);
        verify(media).remove(List.of("first"));
        verify(deletion).delete(42L, 7L);
        verifyNoMoreInteractions(reader, media, deletion);
    }

    private PostSnapshot post(long author, String board, List<String> urls) {
        var time = LocalDateTime.of(2026, 10, 19, 9, 0);
        return new PostSnapshot(42L, author, "preserved title", "preserved content", board, urls, time, time);
    }
}
