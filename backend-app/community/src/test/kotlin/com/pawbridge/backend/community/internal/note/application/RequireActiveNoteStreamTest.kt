package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RequireActiveNoteStreamTest {
    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `0 이하의 호출자 ID가 주어졌을 때__쪽지 스트림 접근을 확인하면__회원 조회 전에 로그인 거절로 끝난다`(id: Long) {
        // given
        val members = mockk<ContactMemberQuery>()

        // when
        val thrown = assertThatThrownBy { RequireActiveNoteStream(members).requireActive(id) }

        // then
        thrown.isInstanceOfSatisfying(NoteStreamRejectedException::class.java) { failure ->
            assertThat(failure.reason()).isEqualTo(NoteStreamRejectedException.Reason.LOGIN)
        }
        confirmVerified(members)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `현재 회원 상태가 활성이거나 비활성일 때__쪽지 스트림 접근을 확인하면__호출자만 조회하고 활성 정책을 지킨다`(active: Boolean) {
        // given
        val members = mockk<ContactMemberQuery>()
        val guard = RequireActiveNoteStream(members)

        every { members.get(any()) } returns ContactMember(7L, " 原文 ", active, !active)

        // when
        val failure = runCatching { guard.requireActive(7) }.exceptionOrNull()

        // then
        if (active) {
            assertThat(failure).isNull()
        } else {
            assertThat(failure).isInstanceOfSatisfying(NoteStreamRejectedException::class.java) { rejected ->
                assertThat(rejected.reason()).isEqualTo(NoteStreamRejectedException.Reason.INACTIVE)
            }
        }
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members)
    }

    @Test
    fun `회원 조회를 쓸 수 없을 때__쪽지 스트림 접근을 확인하면__대체 경로 없이 타입 있는 실패를 그대로 전파한다`() {
        // given
        val members = mockk<ContactMemberQuery>()
        val failure = ContactMemberUnavailableException(IllegalStateException("synthetic-private"))

        every { members.get(any()) } throws failure

        // when
        val thrown = assertThatThrownBy { RequireActiveNoteStream(members).requireActive(7) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members)
    }
}
