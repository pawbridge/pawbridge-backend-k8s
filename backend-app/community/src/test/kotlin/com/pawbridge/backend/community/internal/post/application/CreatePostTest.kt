package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import com.pawbridge.backend.community.support.postMediaUploader
import com.pawbridge.backend.community.support.postWriter
import com.pawbridge.backend.community.support.userNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

class CreatePostTest {

    @ParameterizedTest
    @EnumSource(value = PostBoardType::class, names = ["PROTECTION", "ADOPTION", "COMMUNICATION"])
    fun given__general_board__when__create__then__upload_nickname_and_committed_snapshot_determine_response(
        board: PostBoardType,
    ) {
        val calls = ArrayList<String>()
        val uploads = listOf(PostUpload("photo.png", "image/png", 3L, PostUpload.Content { throw AssertionError() }))
        val create = CreatePost(
            postMediaUploader { files ->
                assertSame(uploads, files)
                calls.add("upload")
                URLS
            },
            userNicknameQuery { authorId ->
                assertEquals(listOf("upload"), calls)
                assertEquals(7L, authorId)
                calls.add("nickname")
                Optional.of("반려친구")
            },
            postWriter { post ->
                assertEquals(listOf("upload", "nickname"), calls)
                assertEquals(PostToCreate(7L, "제목", "본문", board, URLS), post)
                calls.add("writer-commit")
                snapshot(post)
            },
        )

        val result = create.create(7L, "제목", "본문", board, uploads)

        assertEquals(listOf("upload", "nickname", "writer-commit"), calls)
        assertEquals(PostDetails(42L, 7L, "반려친구", "제목", "본문", board.name, URLS, NOW, NOW), result)
    }

    @ParameterizedTest
    @EnumSource(value = PostBoardType::class, names = ["MISSING", "REPORT"])
    fun given__report_board__when__create__then__reject_before_any_upload_nickname_or_write(board: PostBoardType) {
        val create = CreatePost(
            postMediaUploader { throw AssertionError("No media upload") },
            userNicknameQuery { throw AssertionError("No nickname read") },
            postWriter { throw AssertionError("No post write") },
        )

        val failure = assertThrows(PostBoardNotAllowedException::class.java) {
            create.create(7L, "제목", "본문", board, listOf())
        }

        assertEquals("실종·목격 제보는 전용 등록 화면에서 작성해 주세요.", failure.message)
    }

    @Test
    fun given__missing_user__when__create__then__writer_still_commits_with_author_id_fallback() {
        assertEquals("사용자7", createWithNickname(userNicknameQuery { Optional.empty() }).authorNickname)
    }

    @Test
    fun given__unavailable_nickname__when__create__then__writer_still_commits_with_fallback() {
        val unavailable = userNicknameQuery {
            throw UserNicknameUnavailableException(IllegalStateException("unavailable"))
        }
        assertEquals("사용자7", createWithNickname(unavailable).authorNickname)
    }

    @Test
    fun given__empty_nickname__when__create__then__legacy_empty_body_is_null() {
        assertNull(createWithNickname(userNicknameQuery { Optional.of("") }).authorNickname)
    }

    @Test
    fun given__blank_nickname_and_post_fields__when__create__then__existing_blank_values_are_preserved() {
        val result = createWithNickname(userNicknameQuery { Optional.of("  \t ") })

        assertEquals("  \t ", result.authorNickname)
        assertEquals("", result.title)
        assertEquals(" ", result.content)
    }

    @Test
    fun given__unexpected_nickname_failure_after_upload__when__create__then__failure_propagates_before_writer() {
        val failure = IllegalArgumentException("programming failure")
        val create = CreatePost(
            postMediaUploader { URLS },
            userNicknameQuery { throw failure },
            postWriter { throw AssertionError("No write after unexpected nickname failure") },
        )

        assertSame(
            failure,
            assertThrows(IllegalArgumentException::class.java) {
                create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, listOf())
            },
        )
    }

    @Test
    fun given__upload_failure__when__create__then__nickname_and_writer_are_not_called() {
        val failure = RuntimeException("partial upload")
        val create = CreatePost(
            postMediaUploader { throw failure },
            userNicknameQuery { throw AssertionError("No nickname read") },
            postWriter { throw AssertionError("No post write") },
        )

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) {
                create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, listOf())
            },
        )
    }

    @Test
    fun given__writer_commit_outcome_unknown__when__create__then__failure_propagates_without_upload_or_write_retry() {
        val uploads = AtomicInteger()
        val writes = AtomicInteger()
        val failure = RuntimeException("commit response unavailable")
        val create = CreatePost(
            postMediaUploader {
                uploads.incrementAndGet()
                URLS
            },
            userNicknameQuery { Optional.of("반려친구") },
            postWriter {
                writes.incrementAndGet()
                throw failure
            },
        )

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) {
                create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, listOf())
            },
        )
        assertEquals(1, uploads.get())
        assertEquals(1, writes.get())
    }

    private fun createWithNickname(nicknames: UserNicknameQuery): PostDetails =
        CreatePost(postMediaUploader { listOf() }, nicknames, postWriter { post -> snapshot(post) })
            .create(7L, "", " ", PostBoardType.PROTECTION, listOf())

    private fun snapshot(post: PostToCreate): PostSnapshot =
        PostSnapshot(
            42L,
            post.authorId(),
            post.title(),
            post.content(),
            post.boardType().name,
            post.imageUrls(),
            NOW,
            NOW,
        )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 18, 12, 30, 10, 123456789)
        val URLS = listOf("https://media.example.test/first.png", "https://media.example.test/second.mp4")
    }
}
