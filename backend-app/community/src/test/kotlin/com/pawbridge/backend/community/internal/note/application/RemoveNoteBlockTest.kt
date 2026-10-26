package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

class RemoveNoteBlockTest {
    @ParameterizedTest
    @MethodSource("invalidIds")
    fun `0 이하의 현재 회원 또는 대상 ID가 주어졌을 때__차단을 풀면__트랜잭션 포트와 회원 조회 전에 로그인 거절한다`(
        current: Long,
        target: Long,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val removal = mockk<NoteBlockRemoval>()
        val service = RemoveNoteBlock(members, removal)

        // when
        val failure = assertThrows(NoteBlockRemovalRejectedException::class.java) { service.remove(current, target) }

        // then
        assertEquals(NoteBlockRemovalRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, removal)
    }

    @ParameterizedTest
    @ValueSource(longs = [7L, Long.MAX_VALUE])
    fun `유효한 자기 자신 또는 다른 대상이 주어졌을 때__차단을 풀면__대상 조회 없이 현재 회원 조회를 제거 가드로 미뤄 한 번만 한다`(
        target: Long,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val removal = mockk<NoteBlockRemoval>()
        val events = ArrayList<String>()

        every { members.get(any()) } answers {
            events.add("current")
            ContactMember(7L, " 原文 ", true, false)
        }
        every { removal.remove(any(), any(), any()) } answers {
            events.add("before-guard")
            thirdArg<Runnable>().run()
            events.add("after-guard")
        }

        // when
        RemoveNoteBlock(members, removal).remove(7L, target)

        // then
        assertEquals(listOf("before-guard", "current", "after-guard"), events)
        verify(exactly = 1) { removal.remove(7L, target, any()) }
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, removal)
    }

    @Test
    fun `비활성 현재 회원이 주어졌을 때__제거가 가드를 실행하면__비활성으로 거절하고 제거 이어가기를 멈춘다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val removal = mockk<NoteBlockRemoval>()
        val events = ArrayList<String>()

        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)
        every { removal.remove(any(), any(), any()) } answers {
            events.add("before-guard")
            thirdArg<Runnable>().run()
            events.add("after-guard")
        }

        // when
        val failure = assertThrows(NoteBlockRemovalRejectedException::class.java) {
            RemoveNoteBlock(members, removal).remove(7L, 8L)
        }

        // then
        assertEquals(NoteBlockRemovalRejectedException.Reason.INACTIVE, failure.reason())
        assertEquals(listOf("before-guard"), events)
        verify(exactly = 1) { removal.remove(7L, 8L, any()) }
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, removal)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `제거 또는 현재 회원 조회가 실패할 때__차단을 풀면__대체 경로나 재시도 없이 같은 실패를 전파한다`(
        phase: FailurePhase,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val removal = mockk<NoteBlockRemoval>()
        val failure: RuntimeException =
            if (phase == FailurePhase.GUARD) {
                ContactMemberUnavailableException(IllegalStateException("private member failure"))
            } else {
                IllegalStateException("private removal failure")
            }

        if (phase != FailurePhase.BEFORE_GUARD) {
            if (phase == FailurePhase.GUARD) {
                every { members.get(any()) } throws failure
            } else {
                every { members.get(any()) } returns ContactMember(7L, "", true, false)
            }
        }
        every { removal.remove(any(), any(), any()) } answers {
            if (phase == FailurePhase.BEFORE_GUARD) throw failure
            thirdArg<Runnable>().run()
            throw failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { RemoveNoteBlock(members, removal).remove(7L, 8L) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { removal.remove(7L, 8L, any()) }
        if (phase != FailurePhase.BEFORE_GUARD) {
            verify(exactly = 1) { members.get(7L) }
        }
        confirmVerified(members, removal)
    }

    enum class FailurePhase { BEFORE_GUARD, GUARD, AFTER_GUARD }

    companion object {
        @JvmStatic
        fun invalidIds(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0L, 8L),
                Arguments.of(-1L, 8L),
                Arguments.of(7L, 0L),
                Arguments.of(7L, -1L),
            )
    }
}
