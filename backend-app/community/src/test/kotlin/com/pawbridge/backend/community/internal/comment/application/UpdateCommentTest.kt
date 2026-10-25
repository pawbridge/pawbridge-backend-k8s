package com.pawbridge.backend.community.internal.comment.application

import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

class UpdateCommentTest {
    private val comments = mock(CommentReader::class.java)
    private val nicknames = mock(UserNicknameQuery::class.java)
    private val modification = mock(CommentModification::class.java)
    private val update = UpdateComment(comments, nicknames, modification)

    @Test
    fun given__owned_comment_and_changed_content__when__update__then__read_nickname_and_committed_write_keep_original_response_times() {
        whenever(comments.findById(13L)).thenReturn(Optional.of(snapshot()))
        whenever(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"))

        assertEquals(
            CommentDetails(13L, 42L, 7L, "작성자", CONTENT, CREATED, UPDATED),
            update.update(13L, 7L, CONTENT),
        )
        val order = inOrder(comments, nicknames, modification)
        order.verify(comments).findById(13L)
        order.verify(nicknames).findNickname(7L)
        order.verify(modification).update(13L, CONTENT)
        order.verifyNoMoreInteractions()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__content_changed_after_snapshot__when__update__then__initial_content_alone_decides_whether_to_write(
        changed: Boolean,
    ) {
        whenever(comments.findById(13L)).thenReturn(Optional.of(snapshot()))
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

        assertEquals(
            CommentDetails(13L, 42L, 7L, "작성자", requested, CREATED, UPDATED),
            UpdateComment(comments, nickname, modifying).update(13L, 7L, requested),
        )
        assertEquals(CONTENT, stored.get())
        assertEquals(1, nicknameReads.get())
        assertEquals(if (changed) 1 else 0, writes.get())
    }

    @Test
    fun given__absent_or_deleted_comment__when__update_by_another_author__then__not_found_precedes_nickname_and_write() {
        whenever(comments.findById(13L)).thenReturn(Optional.empty())

        val failure = assertThrows(CommentNotFoundException::class.java) { update.update(13L, 99L, CONTENT) }
        assertEquals("댓글을 찾을 수 없습니다.", failure.message)
        verifyNoInteractions(nicknames, modification)
    }

    @Test
    fun given__another_authors_comment__when__update_with_same_content__then__ownership_precedes_nickname_and_write() {
        whenever(comments.findById(13L)).thenReturn(Optional.of(snapshot()))

        val failure = assertThrows(CommentNotOwnedException::class.java) { update.update(13L, 99L, ORIGINAL) }
        assertEquals("댓글에 접근할 권한이 없습니다.", failure.message)
        verifyNoInteractions(nicknames, modification)
    }

    @Test
    fun given__snapshot_read_failure__when__update__then__original_failure_prevents_nickname_and_write() {
        val failure = IllegalStateException("read failed")
        whenever(comments.findById(13L)).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { update.update(13L, 7L, CONTENT) },
        )
        verifyNoInteractions(nicknames, modification)
    }

    @ParameterizedTest
    @MethodSource("nicknameOutcomes")
    fun given__defined_nickname_outcome__when__update__then__existing_fallback_and_raw_nickname_policy_are_preserved(
        nickname: UserNicknameQuery,
        expected: String?,
    ) {
        whenever(comments.findById(13L)).thenReturn(Optional.of(snapshot()))

        assertEquals(
            CommentDetails(13L, 42L, 7L, expected, CONTENT, CREATED, UPDATED),
            UpdateComment(comments, nickname, modification).update(13L, 7L, CONTENT),
        )
        val order = inOrder(comments, modification)
        order.verify(comments).findById(13L)
        order.verify(modification).update(13L, CONTENT)
        order.verifyNoMoreInteractions()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__unexpected_nickname_failure__when__updating_changed_or_same_content__then__original_failure_prevents_write(
        changed: Boolean,
    ) {
        whenever(comments.findById(13L)).thenReturn(Optional.of(snapshot()))
        val failure = IllegalArgumentException("unexpected User failure")
        whenever(nicknames.findNickname(7L)).thenThrow(failure)

        assertSame(
            failure,
            assertThrows(IllegalArgumentException::class.java) {
                update.update(13L, 7L, if (changed) CONTENT else ORIGINAL)
            },
        )
        verifyNoInteractions(modification)
    }

    @Test
    fun given__modification_commit_failure__when__update__then__failure_propagates_without_response_or_retry() {
        whenever(comments.findById(13L)).thenReturn(Optional.of(snapshot()))
        whenever(nicknames.findNickname(7L)).thenReturn(Optional.of("작성자"))
        val failure = IllegalStateException("commit outcome unknown")
        doThrow(failure).`when`(modification).update(13L, CONTENT)

        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) { update.update(13L, 7L, CONTENT) },
        )
        val order = inOrder(comments, nicknames, modification)
        order.verify(comments).findById(13L)
        order.verify(nicknames).findNickname(7L)
        order.verify(modification).update(13L, CONTENT)
        order.verifyNoMoreInteractions()
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
