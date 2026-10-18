package com.pawbridge.backend.community.internal.post.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CreatePostTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 30, 10, 123456789);
    private static final List<String> URLS = List.of("https://media.example.test/first.png", "https://media.example.test/second.mp4");

    @ParameterizedTest
    @EnumSource(value = PostBoardType.class, names = {"PROTECTION", "ADOPTION", "COMMUNICATION"})
    void given__general_board__when__create__then__upload_nickname_and_committed_snapshot_determine_response(PostBoardType board) {
        List<String> calls = new ArrayList<>();
        List<PostUpload> uploads = List.of(new PostUpload("photo.png", "image/png", 3L, () -> null));
        CreatePost create = new CreatePost(files -> {
            assertSame(uploads, files);
            calls.add("upload");
            return URLS;
        }, authorId -> {
            assertEquals(List.of("upload"), calls);
            assertEquals(7L, authorId);
            calls.add("nickname");
            return Optional.of("반려친구");
        }, post -> {
            assertEquals(List.of("upload", "nickname"), calls);
            assertEquals(new PostToCreate(7L, "제목", "본문", board, URLS), post);
            calls.add("writer-commit");
            return snapshot(post);
        });

        PostDetails result = create.create(7L, "제목", "본문", board, uploads);

        assertEquals(List.of("upload", "nickname", "writer-commit"), calls);
        assertEquals(new PostDetails(42L, 7L, "반려친구", "제목", "본문", board.name(), URLS, NOW, NOW), result);
    }

    @ParameterizedTest
    @EnumSource(value = PostBoardType.class, names = {"MISSING", "REPORT"})
    void given__report_board__when__create__then__reject_before_any_upload_nickname_or_write(PostBoardType board) {
        CreatePost create = new CreatePost(files -> { throw new AssertionError("No media upload"); },
                authorId -> { throw new AssertionError("No nickname read"); },
                post -> { throw new AssertionError("No post write"); });

        PostBoardNotAllowedException failure = assertThrows(PostBoardNotAllowedException.class,
                () -> create.create(7L, "제목", "본문", board, List.of()));

        assertEquals("실종·목격 제보는 전용 등록 화면에서 작성해 주세요.", failure.getMessage());
    }

    @Test
    void given__missing_user__when__create__then__writer_still_commits_with_author_id_fallback() {
        assertEquals("사용자7", createWithNickname(authorId -> Optional.empty()).authorNickname());
    }

    @Test
    void given__unavailable_nickname__when__create__then__writer_still_commits_with_fallback() {
        assertEquals("사용자7", createWithNickname(authorId -> {
            throw new UserNicknameUnavailableException(new IllegalStateException("unavailable"));
        }).authorNickname());
    }

    @Test
    void given__empty_nickname__when__create__then__legacy_empty_body_is_null() {
        assertNull(createWithNickname(authorId -> Optional.of("")).authorNickname());
    }

    @Test
    void given__blank_nickname_and_post_fields__when__create__then__existing_blank_values_are_preserved() {
        PostDetails result = createWithNickname(authorId -> Optional.of("  \t "));

        assertEquals("  \t ", result.authorNickname());
        assertEquals("", result.title());
        assertEquals(" ", result.content());
    }

    @Test
    void given__unexpected_nickname_failure_after_upload__when__create__then__failure_propagates_before_writer() {
        IllegalArgumentException failure = new IllegalArgumentException("programming failure");
        CreatePost create = new CreatePost(files -> URLS,
                authorId -> { throw failure; },
                post -> { throw new AssertionError("No write after unexpected nickname failure"); });

        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, List.of())));
    }

    @Test
    void given__upload_failure__when__create__then__nickname_and_writer_are_not_called() {
        RuntimeException failure = new RuntimeException("partial upload");
        CreatePost create = new CreatePost(files -> { throw failure; },
                authorId -> { throw new AssertionError("No nickname read"); },
                post -> { throw new AssertionError("No post write"); });

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, List.of())));
    }

    @Test
    void given__writer_commit_outcome_unknown__when__create__then__failure_propagates_without_upload_or_write_retry() {
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        RuntimeException failure = new RuntimeException("commit response unavailable");
        CreatePost create = new CreatePost(files -> {
            uploads.incrementAndGet();
            return URLS;
        }, authorId -> Optional.of("반려친구"), post -> {
            writes.incrementAndGet();
            throw failure;
        });

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, List.of())));
        assertEquals(1, uploads.get());
        assertEquals(1, writes.get());
    }

    private PostDetails createWithNickname(UserNicknameQuery nicknames) {
        return new CreatePost(files -> List.of(), nicknames, this::snapshot)
                .create(7L, "", " ", PostBoardType.PROTECTION, List.of());
    }

    private PostSnapshot snapshot(PostToCreate post) {
        return new PostSnapshot(42L, post.authorId(), post.title(), post.content(), post.boardType().name(),
                post.imageUrls(), NOW, NOW);
    }
}
