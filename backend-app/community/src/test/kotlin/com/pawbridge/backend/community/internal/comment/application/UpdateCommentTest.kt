package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

class UpdateCommentTest {
    private val comments = mockk<CommentReader>()
    private val nicknames = mockk<UserNicknameQuery>()
    private val modification = mockk<CommentModification>()
    private val update = UpdateComment(comments, nicknames, modification)

    @Test
    fun `본인 댓글의 내용을 바꿀 때__수정하면__읽기와 닉네임 조회 뒤 쓰고 원래 생성 수정 시각으로 응답한다`() {
        // given
        val stored = snapshot()

        every { comments.findById(any()) } returns Optional.of(stored)
        every { nicknames.findNickname(any()) } returns Optional.of("작성자")
        justRun { modification.update(any(), any()) }

        // when
        val result = update.update(13L, 7L, CONTENT)

        // then
        assertEquals(CommentDetails(13L, 42L, 7L, "작성자", CONTENT, CREATED, UPDATED), result)
        verifySequence {
            comments.findById(13L)
            nicknames.findNickname(7L)
            modification.update(13L, CONTENT)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `스냅샷 이후 저장된 내용이 바뀌었을 때__수정하면__처음 읽은 내용만으로 쓸지 정한다`(
        changed: Boolean,
    ) {
        // given
        val stored = AtomicReference(ORIGINAL)
        val nicknameReads = AtomicInteger()
        val writes = AtomicInteger()
        val nickname = object : UserNicknameQuery {
            override fun findNickname(userId: Long): Optional<String> {
                assertEquals(7L, userId)
                nicknameReads.incrementAndGet()
                stored.set(CONTENT)
                return Optional.of("작성자")
            }
        }
        val modifying = object : CommentModification {
            override fun update(commentId: Long, content: String?) {
                assertEquals(13L, commentId)
                writes.incrementAndGet()
                stored.set(content)
            }
        }
        val requested = if (changed) CONTENT else ORIGINAL

        every { comments.findById(any()) } returns Optional.of(snapshot())

        // when
        val result = UpdateComment(comments, nickname, modifying).update(13L, 7L, requested)

        // then
        assertEquals(CommentDetails(13L, 42L, 7L, "작성자", requested, CREATED, UPDATED), result)
        assertEquals(CONTENT, stored.get())
        assertEquals(1, nicknameReads.get())
        assertEquals(if (changed) 1 else 0, writes.get())
        verify(exactly = 1) { comments.findById(13L) }
    }

    @Test
    fun `없거나 삭제된 댓글일 때__다른 작성자가 수정하면__닉네임 조회와 쓰기보다 먼저 찾을 수 없음으로 실패한다`() {
        // given
        every { comments.findById(any()) } returns Optional.empty()

        // when
        val failure = assertThrows(CommentNotFoundException::class.java) { update.update(13L, 99L, CONTENT) }

        // then
        assertEquals("댓글을 찾을 수 없습니다.", failure.message)
        verify(exactly = 1) { comments.findById(13L) }
        confirmVerified(comments, nicknames, modification)
    }

    @Test
    fun `다른 작성자의 댓글일 때__같은 내용으로 수정하면__닉네임 조회와 쓰기보다 먼저 소유권으로 거부한다`() {
        // given
        every { comments.findById(any()) } returns Optional.of(snapshot())

        // when
        val failure = assertThrows(CommentNotOwnedException::class.java) { update.update(13L, 99L, ORIGINAL) }

        // then
        assertEquals("댓글에 접근할 권한이 없습니다.", failure.message)
        verify(exactly = 1) { comments.findById(13L) }
        confirmVerified(comments, nicknames, modification)
    }

    @Test
    fun `스냅샷 조회가 실패할 때__수정하면__원래 예외로 실패하고 닉네임 조회와 쓰기를 하지 않는다`() {
        // given
        val failure = IllegalStateException("read failed")

        every { comments.findById(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { update.update(13L, 7L, CONTENT) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { comments.findById(13L) }
        confirmVerified(comments, nicknames, modification)
    }

    @ParameterizedTest
    @MethodSource("nicknameOutcomes")
    fun `정해진 닉네임 조회 결과가 주어졌을 때__수정하면__기존 대체 닉네임과 원본 닉네임 정책을 지킨다`(
        nickname: UserNicknameQuery,
        expected: String?,
    ) {
        // given
        every { comments.findById(any()) } returns Optional.of(snapshot())
        justRun { modification.update(any(), any()) }

        // when
        val result = UpdateComment(comments, nickname, modification).update(13L, 7L, CONTENT)

        // then
        assertEquals(CommentDetails(13L, 42L, 7L, expected, CONTENT, CREATED, UPDATED), result)
        verifySequence {
            comments.findById(13L)
            modification.update(13L, CONTENT)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `닉네임 조회가 예상 밖으로 실패할 때__바뀐 내용이나 같은 내용으로 수정하면__원래 예외로 실패하고 쓰지 않는다`(
        changed: Boolean,
    ) {
        // given
        val failure = IllegalArgumentException("unexpected User failure")

        every { comments.findById(any()) } returns Optional.of(snapshot())
        every { nicknames.findNickname(any()) } throws failure

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            update.update(13L, 7L, if (changed) CONTENT else ORIGINAL)
        }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { comments.findById(13L) }
        verify(exactly = 1) { nicknames.findNickname(7L) }
        confirmVerified(comments, nicknames, modification)
    }

    @Test
    fun `수정 커밋이 실패할 때__수정하면__응답이나 재시도 없이 같은 예외를 전파한다`() {
        // given
        val failure = IllegalStateException("commit outcome unknown")

        every { comments.findById(any()) } returns Optional.of(snapshot())
        every { nicknames.findNickname(any()) } returns Optional.of("작성자")
        every { modification.update(any(), any()) } throws failure

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { update.update(13L, 7L, CONTENT) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            comments.findById(13L)
            nicknames.findNickname(7L)
            modification.update(13L, CONTENT)
        }
    }

    private fun snapshot(): CommentSnapshot = CommentSnapshot(13L, 42L, 7L, ORIGINAL, CREATED, UPDATED)

    companion object {
        private val CREATED = LocalDateTime.of(2026, 10, 18, 1, 2, 3, 123456789)
        private val UPDATED = CREATED.plusDays(1)
        private const val ORIGINAL = "기존 댓글"
        private const val CONTENT = "  수정 원문\n  "

        @JvmStatic
        fun nicknameOutcomes(): Stream<Arguments> =
            Stream.of(
                Arguments.of(nicknameQuery { Optional.of("작성자") }, "작성자"),
                Arguments.of(nicknameQuery { Optional.empty() }, "사용자7"),
                Arguments.of(
                    nicknameQuery {
                        throw UserNicknameUnavailableException(IllegalStateException("unavailable"))
                    },
                    "사용자7",
                ),
                Arguments.of(nicknameQuery { Optional.of("") }, null),
                Arguments.of(nicknameQuery { Optional.of("  \t ") }, "  \t "),
            )

        private fun nicknameQuery(lookup: (Long) -> Optional<String>): UserNicknameQuery =
            object : UserNicknameQuery {
                override fun findNickname(userId: Long): Optional<String> = lookup(userId)
            }
    }
}
