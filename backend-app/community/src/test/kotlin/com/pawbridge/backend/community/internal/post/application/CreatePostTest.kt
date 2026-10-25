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
    fun `일반 게시판일 때__생성하면__업로드와 닉네임과 저장된 스냅샷으로 응답을 정한다`(
        board: PostBoardType,
    ) {
        // given
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

        // when
        val result = create.create(7L, "제목", "본문", board, uploads)

        // then
        assertEquals(listOf("upload", "nickname", "writer-commit"), calls)
        assertEquals(PostDetails(42L, 7L, "반려친구", "제목", "본문", board.name, URLS, NOW, NOW), result)
    }

    @ParameterizedTest
    @EnumSource(value = PostBoardType::class, names = ["MISSING", "REPORT"])
    fun `제보 게시판일 때__생성하면__업로드와 닉네임 조회와 저장 전에 거부한다`(board: PostBoardType) {
        // given
        val create = CreatePost(
            postMediaUploader { throw AssertionError("No media upload") },
            userNicknameQuery { throw AssertionError("No nickname read") },
            postWriter { throw AssertionError("No post write") },
        )

        // when
        val failure = assertThrows(PostBoardNotAllowedException::class.java) {
            create.create(7L, "제목", "본문", board, listOf())
        }

        // then
        assertEquals("실종·목격 제보는 전용 등록 화면에서 작성해 주세요.", failure.message)
    }

    @Test
    fun `작성자가 없을 때__생성하면__작성자 번호 기반 대체 이름으로 저장을 계속한다`() {
        // given
        val nicknames = userNicknameQuery { Optional.empty() }

        // when
        val result = createWithNickname(nicknames)

        // then
        assertEquals("사용자7", result.authorNickname)
    }

    @Test
    fun `닉네임을 조회할 수 없을 때__생성하면__대체 이름으로 저장을 계속한다`() {
        // given
        val unavailable = userNicknameQuery {
            throw UserNicknameUnavailableException(IllegalStateException("unavailable"))
        }

        // when
        val result = createWithNickname(unavailable)

        // then
        assertEquals("사용자7", result.authorNickname)
    }

    @Test
    fun `닉네임이 빈 문자열일 때__생성하면__기존 동작대로 null로 나타낸다`() {
        // given
        val nicknames = userNicknameQuery { Optional.of("") }

        // when
        val result = createWithNickname(nicknames)

        // then
        assertNull(result.authorNickname)
    }

    @Test
    fun `닉네임과 제목과 본문이 공백일 때__생성하면__기존 공백 값을 그대로 보존한다`() {
        // given
        val nicknames = userNicknameQuery { Optional.of("  \t ") }

        // when
        val result = createWithNickname(nicknames)

        // then
        assertEquals("  \t ", result.authorNickname)
        assertEquals("", result.title)
        assertEquals(" ", result.content)
    }

    @Test
    fun `업로드 후 닉네임 조회가 예상 밖으로 실패할 때__생성하면__저장 전에 예외를 전파한다`() {
        // given
        val failure = IllegalArgumentException("programming failure")
        val create = CreatePost(
            postMediaUploader { URLS },
            userNicknameQuery { throw failure },
            postWriter { throw AssertionError("No write after unexpected nickname failure") },
        )

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, listOf())
        }

        // then
        assertSame(failure, thrown)
    }

    @Test
    fun `업로드가 실패할 때__생성하면__닉네임 조회와 저장을 호출하지 않는다`() {
        // given
        val failure = RuntimeException("partial upload")
        val create = CreatePost(
            postMediaUploader { throw failure },
            userNicknameQuery { throw AssertionError("No nickname read") },
            postWriter { throw AssertionError("No post write") },
        )

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, listOf())
        }

        // then
        assertSame(failure, thrown)
    }

    @Test
    fun `저장 커밋 결과를 알 수 없을 때__생성하면__업로드와 저장을 재시도하지 않고 예외를 전파한다`() {
        // given
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

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            create.create(7L, "제목", "본문", PostBoardType.COMMUNICATION, listOf())
        }

        // then
        assertSame(failure, thrown)
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
