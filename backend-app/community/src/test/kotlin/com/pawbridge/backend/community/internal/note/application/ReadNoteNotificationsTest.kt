package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.Row
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionSystemException
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ReadNoteNotificationsTest {
    private val members = mockk<ContactMemberQuery>()
    private val notes = mockk<NoteNotificationReader>()
    private val contactMembers = mockk<ContactMembersQuery>()
    private val clock = mockk<Clock>()
    private val query = ReadNoteNotifications(members, notes, contactMembers, clock)

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun `0 이하의 현재 회원 ID가 주어졌을 때__읽으면__회원 조회와 시각 읽기와 SQL 전에 로그인 거절한다`(currentUserId: Long) {
        // when
        val failure = assertThrows(NoteNotificationsRejectedException::class.java) {
            query.read(currentUserId, CURSOR)
        }

        // then
        assertEquals(NoteNotificationsRejectedException.Reason.LOGIN, failure.reason())
        confirmVerified(members, clock, notes, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `호출자가 없거나 탈퇴 대기일 때__읽으면__시각 읽기와 목록과 일괄 조회와 안 읽은 수 조회 전에 비활성으로 거절한다`(
        pending: Boolean,
    ) {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, pending)

        // when
        val failure = assertThrows(NoteNotificationsRejectedException::class.java) { query.read(7L, CURSOR) }

        // then
        assertEquals(NoteNotificationsRejectedException.Reason.INACTIVE, failure.reason())
        verify(exactly = 1) { members.get(7L) }
        confirmVerified(members, clock, notes, contactMembers)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `발신자가 없거나 모두 null일 때__읽으면__일괄 조회를 건너뛰고 마지막에 읽은 원본 안 읽은 수를 돌려준다`(
        nullSender: Boolean,
    ) {
        // given
        val row = row(1, null, null)

        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.read(any(), any(), any()) } returns if (nullSender) listOf(row) else listOf()
        every { notes.unread(any(), any()) } returns Long.MAX_VALUE

        // when
        val result = query.read(7L, CURSOR)

        // then
        val content: List<Notification> =
            if (nullSender) {
                listOf(Notification(row.noteId, "PRIVATE_NOTE", null, "탈퇴한 회원", NOW, false, "/notes/" + row.noteId))
            } else {
                listOf()
            }
        assertEquals(NoteNotifications(content, null, Long.MAX_VALUE), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.read(7L, CURSOR, NOW)
            notes.unread(7L, NOW)
        }
        confirmVerified(contactMembers)
    }

    @ParameterizedTest
    @ValueSource(ints = [20, 21])
    fun `행이 20개 또는 21개일 때__읽으면__보이는 20개만 일괄 조회하고 마지막으로 보인 쪽지를 다음 커서로 쓴다`(
        size: Int,
    ) {
        // given
        val found = (0 until size).map { index ->
            row(index, 100L + index, if (index % 2 == 0) null else NOW.minusSeconds(1))
        }
        val ids = (0 until 20).map { index -> 100L + index }

        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.read(any(), any(), any()) } returns found
        every { contactMembers.getAll(any()) } returns ids.map { id -> ContactMember(id, "회원$id", true, false) }
        every { notes.unread(any(), any()) } returns 31L

        // when
        val result = query.read(7L, null)

        // then
        val expected = found.subList(0, 20).map { row ->
            Notification(
                row.noteId,
                "PRIVATE_NOTE",
                row.senderId,
                "회원" + row.senderId,
                row.createdAt,
                row.readAt != null,
                "/notes/" + row.noteId,
            )
        }
        assertEquals(NoteNotifications(expected, if (size == 21) found[19].noteId else null, 31L), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.read(7L, null, NOW)
            contactMembers.getAll(ids)
            notes.unread(7L, NOW)
        }
    }

    @Test
    fun `중복과 null과 활성과 없음과 탈퇴 대기 발신자가 섞여 있을 때__읽으면__중복 없이 한 번 조회하고 순서와 원문 이름과 null 가능한 행위자 ID를 보존한다`() {
        // given
        val found = listOf(
            row(1, 90L, null), row(2, null, NOW), row(3, 80L, null),
            row(4, 90L, NOW), row(5, 70L, null), row(6, 60L, null), row(7, 50L, null),
        )
        val ids = listOf(90L, 80L, 70L, 60L, 50L)

        every { members.get(any()) } returns CURRENT
        every { clock.instant() } returns NOW
        every { notes.read(any(), any(), any()) } returns found
        every { contactMembers.getAll(any()) } returns listOf(
            ContactMember(90L, " 원문 닉네임 ", true, false),
            ContactMember(80L, "", true, false),
            ContactMember(70L, "  ", true, false),
            ContactMember(60L, "탈퇴한 회원", false, false),
            ContactMember(50L, "탈퇴한 회원", false, true),
        )
        every { notes.unread(any(), any()) } returns 4L

        // when
        val result = query.read(7L, CURSOR)

        // then
        val expected = listOf(
            notification(found[0], 90L, " 원문 닉네임 "),
            notification(found[1], null, "탈퇴한 회원"),
            notification(found[2], 80L, ""),
            notification(found[3], 90L, " 원문 닉네임 "),
            notification(found[4], 70L, "  "),
            notification(found[5], null, "탈퇴한 회원"),
            notification(found[6], null, "탈퇴한 회원"),
        )
        assertEquals(NoteNotifications(expected, null, 4L), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.read(7L, CURSOR, NOW)
            contactMembers.getAll(ids)
            notes.unread(7L, NOW)
        }
    }

    @Test
    fun `호출자 조회와 목록 조회 중 시간이 흘렀을 때__읽으면__호출자 조회 뒤 한 번만 시각을 잡고 안 읽은 수에도 그 시각을 재사용한다`() {
        // given
        var time = NOW.minusSeconds(1)

        every { members.get(any()) } answers {
            time = NOW
            CURRENT
        }
        every { clock.instant() } answers { time }
        every { notes.read(any(), any(), any()) } answers {
            time = NOW.plusSeconds(3600)
            listOf<Row>()
        }
        every { notes.unread(any(), any()) } returns 0L

        // when
        val result = query.read(7L, CURSOR)

        // then
        assertEquals(NoteNotifications(listOf(), null, 0L), result)
        verifySequence {
            members.get(7L)
            clock.instant()
            notes.read(7L, CURSOR, NOW)
            notes.unread(7L, NOW)
        }
        confirmVerified(contactMembers)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `호출자 시각 목록 일괄 조회 또는 안 읽은 수 조회가 실패할 때__읽으면__같은 실패를 전파하고 이후 단계를 멈춘다`(
        phase: FailurePhase,
    ) {
        // given
        val failure: RuntimeException = when (phase) {
            FailurePhase.CALLER, FailurePhase.BATCH ->
                ContactMemberUnavailableException(DataAccessResourceFailureException("private User SQL"))
            FailurePhase.CLOCK -> IllegalStateException("private clock failure")
            FailurePhase.LIST -> DataAccessResourceFailureException("private note SQL")
            FailurePhase.UNREAD -> TransactionSystemException("private unread commit")
        }

        if (phase == FailurePhase.CALLER) {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns CURRENT
        }
        if (phase == FailurePhase.CLOCK) {
            every { clock.instant() } throws failure
        } else {
            every { clock.instant() } returns NOW
        }
        if (phase == FailurePhase.LIST) {
            every { notes.read(any(), any(), any()) } throws failure
        } else {
            every { notes.read(any(), any(), any()) } returns listOf(row(1, 8L, null))
        }
        if (phase == FailurePhase.BATCH) {
            every { contactMembers.getAll(any()) } throws failure
        } else {
            every { contactMembers.getAll(any()) } returns listOf(ContactMember(8L, "회원", true, false))
        }
        if (phase == FailurePhase.UNREAD) {
            every { notes.unread(any(), any()) } throws failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.read(7L, CURSOR) }

        // then
        assertSame(failure, thrown)
        verifySequence {
            members.get(7L)
            if (phase != FailurePhase.CALLER) {
                clock.instant()
            }
            if (phase == FailurePhase.LIST || phase == FailurePhase.BATCH || phase == FailurePhase.UNREAD) {
                notes.read(7L, CURSOR, NOW)
            }
            if (phase == FailurePhase.BATCH || phase == FailurePhase.UNREAD) {
                contactMembers.getAll(listOf(8L))
            }
            if (phase == FailurePhase.UNREAD) {
                notes.unread(7L, NOW)
            }
        }
    }

    enum class FailurePhase { CALLER, CLOCK, LIST, BATCH, UNREAD }

    private companion object {
        val NOW: Instant = Instant.parse("2031-01-01T14:59:59.123456Z")
        val CURSOR = UUID(1L, 9L)
        val CURRENT = ContactMember(7L, "현재 회원", true, false)

        fun row(index: Int, senderId: Long?, readAt: Instant?): Row =
            Row(UUID(1L, index.toLong()), senderId, NOW, readAt)

        fun notification(row: Row, actorId: Long?, nickname: String?): Notification =
            Notification(
                row.noteId,
                "PRIVATE_NOTE",
                actorId,
                nickname,
                row.createdAt,
                row.readAt != null,
                "/notes/" + row.noteId,
            )
    }
}
