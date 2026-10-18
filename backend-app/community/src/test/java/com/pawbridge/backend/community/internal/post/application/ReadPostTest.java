package com.pawbridge.backend.community.internal.post.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReadPostTest {
    @Test
    void given__post_and_nickname__when__read__then__details_preserve_post_fields() {
        PostSnapshot post = createPost();
        ReadPost readPost = new ReadPost(postId -> {
            assertEquals(post.postId(), postId);
            return Optional.of(post);
        }, authorId -> {
            assertEquals(post.authorId(), authorId);
            return Optional.of("반려친구");
        });

        PostDetails details = readPost.read(post.postId());

        assertEquals(new PostDetails(
                post.postId(), post.authorId(), "반려친구", post.title(), post.content(),
                post.boardType(), post.imageUrls(), post.createdAt(), post.updatedAt()), details);
    }

    @Test
    void given__missing_user__when__read__then__author_id_fallback_is_returned() {
        ReadPost readPost = createReader(authorId -> Optional.empty());

        assertEquals("사용자7", readPost.read(42L).authorNickname());
    }

    @Test
    void given__nickname_unavailable__when__read__then__author_id_fallback_is_returned() {
        ReadPost readPost = createReader(authorId -> {
            throw new UserNicknameUnavailableException(new IllegalStateException("unavailable"));
        });

        assertEquals("사용자7", readPost.read(42L).authorNickname());
    }

    @Test
    void given__empty_nickname__when__read__then__legacy_empty_body_is_represented_as_null() {
        ReadPost readPost = createReader(authorId -> Optional.of(""));

        assertNull(readPost.read(42L).authorNickname());
    }

    @Test
    void given__whitespace_nickname__when__read__then__whitespace_is_preserved() {
        ReadPost readPost = createReader(authorId -> Optional.of("  \t "));

        assertEquals("  \t ", readPost.read(42L).authorNickname());
    }

    @Test
    void given__missing_post__when__read__then__not_found_prevents_nickname_lookup() {
        ReadPost readPost = new ReadPost(postId -> Optional.empty(), authorId -> {
            throw new AssertionError("Nickname lookup must follow a successful post lookup");
        });

        assertThrows(PostNotFoundException.class, () -> readPost.read(-1L));
    }

    @Test
    void given__post_read_failure__when__read__then__failure_propagates_before_nickname_lookup() {
        IllegalStateException failure = new IllegalStateException("post mapping failed");
        ReadPost readPost = new ReadPost(postId -> {
            throw failure;
        }, authorId -> {
            throw new AssertionError("Nickname lookup must follow a successful post lookup");
        });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> readPost.read(42L)));
    }

    @Test
    void given__unexpected_nickname_failure__when__read__then__failure_is_not_hidden_by_fallback() {
        IllegalArgumentException failure = new IllegalArgumentException("programming failure");
        ReadPost readPost = createReader(authorId -> {
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> readPost.read(42L)));
    }

    private ReadPost createReader(UserNicknameQuery nicknameQuery) {
        return new ReadPost(postId -> Optional.of(createPost()), nicknameQuery);
    }

    private PostSnapshot createPost() {
        return new PostSnapshot(
                42L, 7L, "함께 찾아주세요", "게시글 본문", "MISSING",
                Arrays.asList("https://example.test/한글.png", null, "https://example.test/한글.png"),
                LocalDateTime.of(2026, 10, 18, 23, 59, 59, 123456000),
                LocalDateTime.of(2026, 10, 19, 0, 0, 1, 654321000));
    }
}
