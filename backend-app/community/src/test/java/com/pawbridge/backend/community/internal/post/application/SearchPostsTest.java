package com.pawbridge.backend.community.internal.post.application;

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SearchPostsTest {
    private final PostSearchReader postSearchReader = mock(PostSearchReader.class);
    private final UserNicknameQuery nicknameQuery = mock(UserNicknameQuery.class);
    private final SearchPosts searchPosts = new SearchPosts(postSearchReader, nicknameQuery);

    @Test
    void given__no_posts__when__search__then__empty_list_without_nickname_lookup() {
        when(postSearchReader.search("지산이를")).thenReturn(List.of());

        assertEquals(List.of(), searchPosts.search("지산이를"));
        verifyNoInteractions(nicknameQuery);
    }

    @Test
    void given__ordered_posts_with_repeated_author__when__search__then__each_row_is_enriched_in_order() {
        PostSnapshot first = post(42L, 7L);
        PostSnapshot second = post(41L, 9L);
        PostSnapshot third = post(40L, 7L);
        when(postSearchReader.search("지산이를")).thenReturn(List.of(first, second, third));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.of("첫 닉네임")).thenReturn(Optional.of("다음 닉네임"));
        when(nicknameQuery.findNickname(9L)).thenReturn(Optional.of("다른 작성자"));

        assertEquals(List.of(
                details(first, "첫 닉네임"), details(second, "다른 작성자"), details(third, "다음 닉네임")),
                searchPosts.search("지산이를"));
        InOrder order = inOrder(postSearchReader, nicknameQuery);
        order.verify(postSearchReader).search("지산이를");
        order.verify(nicknameQuery).findNickname(7L);
        order.verify(nicknameQuery).findNickname(9L);
        order.verify(nicknameQuery).findNickname(7L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void given__missing_and_unavailable_nicknames__when__search__then__only_each_affected_row_uses_fallback() {
        PostSnapshot missing = post(42L, 7L);
        PostSnapshot unavailable = post(41L, 8L);
        PostSnapshot available = post(40L, 7L);
        when(postSearchReader.search("지산이를")).thenReturn(List.of(missing, unavailable, available));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.empty()).thenReturn(Optional.of("회복한 닉네임"));
        when(nicknameQuery.findNickname(8L))
                .thenThrow(new UserNicknameUnavailableException(new IllegalStateException("unavailable")));

        assertEquals(List.of(
                details(missing, "사용자7"), details(unavailable, "사용자8"), details(available, "회복한 닉네임")),
                searchPosts.search("지산이를"));
    }

    @Test
    void given__empty_and_whitespace_nicknames__when__search__then__empty_is_null_and_whitespace_is_preserved() {
        PostSnapshot empty = post(42L, 7L);
        PostSnapshot whitespace = post(41L, 8L);
        when(postSearchReader.search("지산이를")).thenReturn(List.of(empty, whitespace));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.of(""));
        when(nicknameQuery.findNickname(8L)).thenReturn(Optional.of("  \t "));

        assertEquals(List.of(details(empty, null), details(whitespace, "  \t ")), searchPosts.search("지산이를"));
    }

    @Test
    void given__post_read_failure__when__search__then__original_failure_prevents_nickname_lookup() {
        IllegalStateException failure = new IllegalStateException("post mapping failed");
        when(postSearchReader.search("지산이를")).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> searchPosts.search("지산이를")));
        verifyNoInteractions(nicknameQuery);
    }

    @Test
    void given__unexpected_nickname_failure__when__search__then__whole_list_fails_and_later_rows_are_not_queried() {
        when(postSearchReader.search("지산이를")).thenReturn(List.of(post(42L, 7L), post(41L, 8L), post(40L, 9L)));
        when(nicknameQuery.findNickname(7L)).thenReturn(Optional.of("정상"));
        IllegalArgumentException failure = new IllegalArgumentException("programming failure");
        when(nicknameQuery.findNickname(8L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> searchPosts.search("지산이를")));
        InOrder order = inOrder(nicknameQuery);
        order.verify(nicknameQuery).findNickname(7L);
        order.verify(nicknameQuery).findNickname(8L);
        order.verifyNoMoreInteractions();
    }

    private PostDetails details(PostSnapshot post, String nickname) {
        return new PostDetails(
                post.postId(), post.authorId(), nickname, post.title(), post.content(), post.boardType(),
                post.imageUrls(), post.createdAt(), post.updatedAt());
    }

    private PostSnapshot post(long postId, long authorId) {
        return new PostSnapshot(
                postId, authorId, "함께 찾아주세요 " + postId, "게시글 본문 " + postId, "MISSING",
                Arrays.asList("https://example.test/한글.png", null, "https://example.test/한글.png"),
                LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000),
                LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000));
    }
}
