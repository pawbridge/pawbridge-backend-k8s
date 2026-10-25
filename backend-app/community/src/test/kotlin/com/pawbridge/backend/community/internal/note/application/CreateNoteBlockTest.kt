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
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier
import java.util.stream.Stream

class CreateNoteBlockTest {
    @ParameterizedTest
    @MethodSource("invalidIds")
    fun `0 이하의 ID가 주어졌을 때__차단을 만들면__자기 포트와 회원 조회와 시각 읽기 전에 로그인 거절한다`(
        current: Long,
        target: Long,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val creation = mockk<NoteBlockCreation>()
        val clock = mockk<Clock>()

        // when
        val failure = assertThrows(NoteBlockCreationRejectedException::class.java) {
            CreateNoteBlock(members, creation, clock).create(current, target)
        }

        // then
        assertEquals(NoteBlockCreationRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, creation, clock)
    }

    @Test
    fun `양수인 자기 자신이 대상일 때__차단을 만들면__트랜잭션 포트와 회원 조회와 시각 읽기 전에 자기 차단으로 거절한다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val creation = mockk<NoteBlockCreation>()
        val clock = mockk<Clock>()

        // when
        val failure = assertThrows(NoteBlockCreationRejectedException::class.java) {
            CreateNoteBlock(members, creation, clock).create(7L, 7L)
        }

        // then
        assertEquals(NoteBlockCreationRejectedException.Reason.SELF, failure.reason())
        confirmVerified(members, creation, clock)
    }

    @Test
    fun `서로 다른 활성 회원이 주어졌을 때__생성 포트가 공급자를 호출하면__현재 회원 대상 회원 시각을 그 순서로 한 번씩 읽는다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val creation = mockk<NoteBlockCreation>()
        val clock = mockk<Clock>()
        val events = ArrayList<String>()

        every { members.get(7L) } answers {
            events.add("current")
            ContactMember(7L, " 原文 ", true, false)
        }
        every { members.get(Long.MAX_VALUE) } answers {
            events.add("target")
            ContactMember(Long.MAX_VALUE, "", true, false)
        }
        every { clock.instant() } answers {
            events.add("clock")
            NOW
        }
        every { creation.create(any(), any(), any()) } answers {
            confirmVerified(members, clock)
            events.add("before-supplier")
            val supplier = thirdArg<Supplier<Instant>>()
            assertEquals(NOW, supplier.get())
            events.add("after-supplier")
        }

        // when
        CreateNoteBlock(members, creation, clock).create(7L, Long.MAX_VALUE)

        // then
        assertEquals(listOf("before-supplier", "current", "target", "clock", "after-supplier"), events)
        verify(exactly = 1) { creation.create(7L, Long.MAX_VALUE, any()) }
        verify(exactly = 1) { members.get(7L) }
        verify(exactly = 1) { members.get(Long.MAX_VALUE) }
        verify(exactly = 1) { clock.instant() }
        confirmVerified(members, creation, clock)
    }

    @ParameterizedTest
    @EnumSource(InactiveSide::class)
    fun `현재 회원 또는 대상 회원이 비활성일 때__공급자가 회원을 확인하면__이후 회원 조회와 시각 읽기와 이어가기 없이 비활성으로 거절한다`(
        side: InactiveSide,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val creation = mockk<NoteBlockCreation>()
        val clock = mockk<Clock>()
        val events = ArrayList<String>()

        every { members.get(7L) } returns
            ContactMember(7L, "탈퇴한 회원", side != InactiveSide.CURRENT, side == InactiveSide.CURRENT)
        if (side == InactiveSide.TARGET) {
            every { members.get(8L) } returns ContactMember(8L, "탈퇴한 회원", false, false)
        }
        every { creation.create(any(), any(), any()) } answers {
            events.add("before-supplier")
            val supplier = thirdArg<Supplier<Instant>>()
            supplier.get()
            events.add("after-supplier")
        }

        // when
        val failure = assertThrows(NoteBlockCreationRejectedException::class.java) {
            CreateNoteBlock(members, creation, clock).create(7L, 8L)
        }

        // then
        assertEquals(NoteBlockCreationRejectedException.Reason.INACTIVE, failure.reason())
        assertEquals(listOf("before-supplier"), events)
        verify(exactly = 1) { creation.create(7L, 8L, any()) }
        verify(exactly = 1) { members.get(7L) }
        if (side == InactiveSide.TARGET) {
            verify(exactly = 1) { members.get(8L) }
        }
        confirmVerified(members, creation, clock)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `현재 회원 대상 회원 또는 시각 읽기가 실패할 때__공급자가 실행되면__같은 실패를 전파하고 재시도 없이 이후 작업을 멈춘다`(
        phase: FailurePhase,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val creation = mockk<NoteBlockCreation>()
        val clock = mockk<Clock>()
        val failure: RuntimeException =
            if (phase == FailurePhase.CLOCK) {
                IllegalStateException("private clock failure")
            } else {
                ContactMemberUnavailableException(IllegalStateException("private member failure"))
            }
        val events = ArrayList<String>()

        if (phase == FailurePhase.CURRENT) {
            every { members.get(7L) } throws failure
        } else {
            every { members.get(7L) } returns ContactMember(7L, "", true, false)
        }
        if (phase == FailurePhase.TARGET) {
            every { members.get(8L) } throws failure
        }
        if (phase == FailurePhase.CLOCK) {
            every { members.get(8L) } returns ContactMember(8L, "", true, false)
            every { clock.instant() } throws failure
        }
        every { creation.create(any(), any(), any()) } answers {
            events.add("before-supplier")
            val supplier = thirdArg<Supplier<Instant>>()
            supplier.get()
            events.add("after-supplier")
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            CreateNoteBlock(members, creation, clock).create(7L, 8L)
        }

        // then
        assertSame(failure, thrown)
        assertEquals(listOf("before-supplier"), events)
        verify(exactly = 1) { creation.create(7L, 8L, any()) }
        verify(exactly = 1) { members.get(7L) }
        if (phase != FailurePhase.CURRENT) {
            verify(exactly = 1) { members.get(8L) }
        }
        if (phase == FailurePhase.CLOCK) {
            verify(exactly = 1) { clock.instant() }
        }
        confirmVerified(members, creation, clock)
    }

    enum class InactiveSide { CURRENT, TARGET }

    enum class FailurePhase { CURRENT, TARGET, CLOCK }

    companion object {
        private val NOW = Instant.parse("2031-01-01T00:00:00.123456Z")

        @JvmStatic
        fun invalidIds(): Stream<Arguments> =
            Stream.of(
                Arguments.of(0L, 8L),
                Arguments.of(-1L, 8L),
                Arguments.of(7L, 0L),
                Arguments.of(7L, -1L),
                Arguments.of(0L, 0L),
            )
    }
}
