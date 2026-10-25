package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.support.TestFaker
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.util.UUID

class MarkPrivateNoteReadTest {
    @Test
    fun `활성 현재 회원이 주어졌을 때__읽음 처리하면__콜백 안에서 현재 회원만 조회하고 캐시 없이 시각을 세 번 읽는다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val events = ArrayList<String>()
        var samples = 0
        // This port models callback placement; it is not transaction or SQL evidence.
        val mutation = NoteReadMutation { current, note, guard, now ->
            assertEquals(7L, current)
            assertEquals(NOTE, note)
            events.add("port")
            confirmVerified(members, clock)
            guard.run()
            for (index in 0 until 3) {
                assertEquals(FIRST.plusSeconds(index.toLong()), now.get())
            }
        }

        every { members.get(any()) } answers {
            events.add("current")
            ContactMember(7L, " raw ", true, false)
        }
        every { clock.instant() } answers {
            samples++
            events.add("clock:$samples")
            FIRST.plusSeconds((samples - 1).toLong())
        }

        // when
        MarkPrivateNoteRead(members, mutation, clock).read(7L, NOTE)

        // then
        assertEquals(listOf("port", "current", "clock:1", "clock:2", "clock:3"), events)
        verify(exactly = 1) { members.get(7L) }
        verify(exactly = 3) { clock.instant() }
        confirmVerified(members, clock)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `0 이하의 직접 호출자와 가드 이전의 포트 실패가 주어졌을 때__읽음 처리하면__앞선 회원 SQL 실패를 그대로 보존한다`(
        current: Long,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val failure = DataIntegrityViolationException("synthetic member CHECK")
        val mutation = NoteReadMutation { actual, note, _, _ ->
            assertEquals(current, actual)
            assertEquals(NOTE, note)
            throw failure
        }

        // when
        val thrown = assertThrows(DataIntegrityViolationException::class.java) {
            MarkPrivateNoteRead(members, mutation, clock).read(current, NOTE)
        }

        // then
        assertSame(failure, thrown)
        confirmVerified(members, clock)
    }

    @Test
    fun `포트가 가드까지 가는 0 이하의 호출자가 주어졌을 때__현재 회원을 확인하면__회원 조회와 시각 읽기 전에 콜백 안에서 로그인 거절한다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        var entered = 0
        val mutation = NoteReadMutation { _, _, guard, _ ->
            entered++
            guard.run()
        }

        // when
        val failure = assertThrows(NoteReadMutationRejectedException::class.java) {
            MarkPrivateNoteRead(members, mutation, clock).read(0L, NOTE)
        }

        // then
        assertEquals(NoteReadMutationRejectedException.Reason.LOGIN, failure.reason())
        assertEquals(1, entered)
        confirmVerified(members, clock)
    }

    @Test
    fun `비활성 현재 회원이 주어졌을 때__포트가 가드를 호출하면__시각을 읽거나 이어가지 않고 비활성으로 거절한다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val events = ArrayList<String>()
        val mutation = NoteReadMutation { _, _, guard, now ->
            events.add("port")
            guard.run()
            events.add("continued")
            now.get()
        }

        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)

        // when
        val failure = assertThrows(NoteReadMutationRejectedException::class.java) {
            MarkPrivateNoteRead(members, mutation, clock).read(7L, NOTE)
        }

        // then
        assertEquals(NoteReadMutationRejectedException.Reason.INACTIVE, failure.reason())
        assertEquals(listOf("port"), events)
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, clock)
    }

    @Test
    fun `포트가 소유한 쪽지 없음만 보고할 때__읽음 처리하면__그 사실을 찾을 수 없음 정책으로 옮긴다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val mutation = NoteReadMutation { _, _, guard, now ->
            guard.run()
            now.get()
            throw NoteReadMutation.MissingOwnedNote()
        }

        every { members.get(any()) } returns ContactMember(7L, "member", true, false)
        every { clock.instant() } returns FIRST

        // when
        val failure = assertThrows(NoteReadMutationRejectedException::class.java) {
            MarkPrivateNoteRead(members, mutation, clock).read(7L, NOTE)
        }

        // then
        assertEquals(NoteReadMutationRejectedException.Reason.NOT_FOUND, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        verify(exactly = 1) { clock.instant() }
        confirmVerified(members, clock)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `회원 조회 또는 시각 읽기가 실패할 때__포트가 애플리케이션 의존성을 호출하면__찾을 수 없음으로 바꾸지 않고 같은 실패를 전파한다`(
        phase: FailurePhase,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val failure: RuntimeException =
            if (phase == FailurePhase.USER) {
                ContactMemberUnavailableException(IllegalStateException("synthetic user"))
            } else {
                IllegalStateException("synthetic clock")
            }
        val mutation = NoteReadMutation { _, _, guard, now ->
            guard.run()
            now.get()
        }

        if (phase == FailurePhase.USER) {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns ContactMember(7L, "member", true, false)
            every { clock.instant() } throws failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            MarkPrivateNoteRead(members, mutation, clock).read(7L, NOTE)
        }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { members.get(7L) }
        if (phase == FailurePhase.CLOCK) {
            verify(exactly = 1) { clock.instant() }
        }
        confirmVerified(members, clock)
    }

    enum class FailurePhase { USER, CLOCK }

    private companion object {
        val NOTE: UUID = TestFaker.uuid()
        val FIRST: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")
    }
}
