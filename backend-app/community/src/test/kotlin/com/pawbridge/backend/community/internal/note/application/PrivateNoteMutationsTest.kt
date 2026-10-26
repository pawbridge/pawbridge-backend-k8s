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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Mark-as-read, delete and set-favorite share one guard contract: the use case hands the persistence port a
 * callback that checks the current member and a clock that is sampled per ownership check. The table runs the
 * same cases against each use case; only the port type, the use case call and the rejection type differ.
 */
class PrivateNoteMutationsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    fun `활성 현재 회원이 주어졌을 때__변경하면__콜백 안에서 현재 회원만 조회하고 캐시 없이 시각을 읽는다`(subject: Subject) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val events = ArrayList<String>()
        var samples = 0
        // This port models callback placement; it is not transaction or SQL evidence.
        val port = Port { current, note, guard, now ->
            assertEquals(7L, current)
            assertEquals(NOTE, note)
            events.add("port")
            confirmVerified(members, clock)
            guard.run()
            for (index in 0 until subject.clockSamples) {
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
        subject.run(members, clock, port, 7L)

        // then
        val clockEvents = (1..subject.clockSamples).map { "clock:$it" }
        assertEquals(listOf("port", "current") + clockEvents, events)
        verify(exactly = 1) { members.get(7L) }
        verify(exactly = subject.clockSamples) { clock.instant() }
        confirmVerified(members, clock)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjectsWithCurrent")
    fun `0 이하의 직접 호출자와 가드 이전의 포트 실패가 주어졌을 때__변경하면__앞선 회원 SQL 실패를 그대로 보존한다`(
        subject: Subject,
        current: Long,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val failure = DataIntegrityViolationException("synthetic member CHECK")
        val port = Port { actual, note, _, _ ->
            assertEquals(current, actual)
            assertEquals(NOTE, note)
            throw failure
        }

        // when
        val thrown = assertThrows(DataIntegrityViolationException::class.java) {
            subject.run(members, clock, port, current)
        }

        // then
        assertSame(failure, thrown)
        confirmVerified(members, clock)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    fun `포트가 가드까지 가는 0 이하의 호출자가 주어졌을 때__현재 회원을 확인하면__회원 조회와 시각 읽기 전에 콜백 안에서 로그인 거절한다`(
        subject: Subject,
    ) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        var entered = 0
        val port = Port { _, _, guard, _ ->
            entered++
            guard.run()
        }

        // when
        val failure = assertThrows(subject.rejectedType) { subject.run(members, clock, port, 0L) }

        // then
        assertEquals("LOGIN", subject.reasonOf(failure))
        assertEquals(1, entered)
        confirmVerified(members, clock)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    fun `비활성 현재 회원이 주어졌을 때__포트가 가드를 호출하면__시각을 읽거나 이어가지 않고 비활성으로 거절한다`(subject: Subject) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val events = ArrayList<String>()
        val port = Port { _, _, guard, now ->
            events.add("port")
            guard.run()
            events.add("continued")
            now.get()
        }

        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)

        // when
        val failure = assertThrows(subject.rejectedType) { subject.run(members, clock, port, 7L) }

        // then
        assertEquals("INACTIVE", subject.reasonOf(failure))
        assertEquals(listOf("port"), events)
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, clock)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    fun `포트가 소유한 쪽지 없음만 보고할 때__변경하면__그 사실을 찾을 수 없음 정책으로 옮긴다`(subject: Subject) {
        // given
        val members = mockk<ContactMemberQuery>()
        val clock = mockk<Clock>()
        val port = Port { _, _, guard, now ->
            guard.run()
            now.get()
            throw subject.missingOwnedNote()
        }

        every { members.get(any()) } returns ContactMember(7L, "member", true, false)
        every { clock.instant() } returns FIRST

        // when
        val failure = assertThrows(subject.rejectedType) { subject.run(members, clock, port, 7L) }

        // then
        assertEquals("NOT_FOUND", subject.reasonOf(failure))
        verify(exactly = 1) { members.get(7L) }
        verify(exactly = 1) { clock.instant() }
        confirmVerified(members, clock)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("subjectsWithPhase")
    fun `회원 조회 또는 시각 읽기가 실패할 때__포트가 애플리케이션 의존성을 호출하면__찾을 수 없음으로 바꾸지 않고 같은 실패를 전파한다`(
        subject: Subject,
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
        val port = Port { _, _, guard, now ->
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
        val thrown = assertThrows(RuntimeException::class.java) { subject.run(members, clock, port, 7L) }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { members.get(7L) }
        if (phase == FailurePhase.CLOCK) {
            verify(exactly = 1) { clock.instant() }
        }
        confirmVerified(members, clock)
    }

    enum class FailurePhase { USER, CLOCK }

    /** The persistence port of any of the three use cases, reduced to the arguments they share. */
    fun interface Port {
        fun call(current: Long, note: UUID?, guard: Runnable, now: Supplier<Instant>)
    }

    class Subject(
        private val name: String,
        val clockSamples: Int,
        val rejectedType: Class<out RuntimeException>,
        val reasonOf: (RuntimeException) -> String,
        val missingOwnedNote: () -> RuntimeException,
        val run: (ContactMemberQuery, Clock, Port, Long) -> Unit,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val NOTE: UUID = TestFaker.uuid()
        private val FIRST: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")

        private fun favorite(favorite: Boolean) = Subject(
            name = "즐겨찾기 $favorite",
            clockSamples = 2,
            rejectedType = NoteFavoriteRejectedException::class.java,
            reasonOf = { (it as NoteFavoriteRejectedException).reason().name },
            missingOwnedNote = { NoteFavoriteMutation.MissingOwnedNote() },
            run = { members, clock, port, current ->
                val mutation = NoteFavoriteMutation { actual, note, requested, guard, now ->
                    assertEquals(favorite, requested)
                    port.call(actual, note, guard, now)
                }
                SetPrivateNoteFavorite(members, mutation, clock).set(current, NOTE, favorite)
            },
        )

        private val SUBJECTS = listOf(
            Subject(
                name = "읽음",
                clockSamples = 3,
                rejectedType = NoteReadMutationRejectedException::class.java,
                reasonOf = { (it as NoteReadMutationRejectedException).reason().name },
                missingOwnedNote = { NoteReadMutation.MissingOwnedNote() },
                run = { members, clock, port, current ->
                    val mutation = NoteReadMutation { actual, note, guard, now -> port.call(actual, note, guard, now) }
                    MarkPrivateNoteRead(members, mutation, clock).read(current, NOTE)
                },
            ),
            Subject(
                name = "삭제",
                clockSamples = 2,
                rejectedType = NoteDeletionRejectedException::class.java,
                reasonOf = { (it as NoteDeletionRejectedException).reason().name },
                missingOwnedNote = { NoteDeletion.MissingOwnedNote() },
                run = { members, clock, port, current ->
                    val mutation = NoteDeletion { actual, note, guard, now -> port.call(actual, note, guard, now) }
                    DeletePrivateNote(members, mutation, clock).delete(current, NOTE)
                },
            ),
            favorite(false),
            favorite(true),
        )

        @JvmStatic
        fun subjects(): Stream<Arguments> = SUBJECTS.stream().map { Arguments.of(it) }

        @JvmStatic
        fun subjectsWithCurrent(): Stream<Arguments> =
            SUBJECTS.stream().flatMap { subject -> Stream.of(0L, -1L).map { Arguments.of(subject, it) } }

        @JvmStatic
        fun subjectsWithPhase(): Stream<Arguments> =
            SUBJECTS.stream().flatMap { subject -> FailurePhase.entries.stream().map { Arguments.of(subject, it) } }
    }
}
