package com.pawbridge.backend.user.internal.nickname.application

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

class ChangeUserNicknameTest {
    private val users = mockk<UserNicknameStore>()
    private val change = ChangeUserNickname(users)

    @Test
    fun `현재 닉네임과 정확히 같을 때__변경하면__조회는 하지만 중복 쿼리와 갱신은 건너뛴다`() {
        // given
        every { users.findNickname(any()) } returns Optional.of("Alice")

        // when
        change.change(52L, "Alice")

        // then
        verify(exactly = 1) { users.findNickname(52L) }
        confirmVerified(users)
    }

    @Test
    fun `회원이 없을 때__변경하면__중복 쿼리와 갱신보다 먼저 회원 없음으로 실패한다`() {
        // given
        every { users.findNickname(any()) } returns Optional.empty()

        // when
        assertThrows(UserNotFoundException::class.java) { change.change(52L, "새닉네임") }

        // then
        verify(exactly = 1) { users.findNickname(52L) }
        confirmVerified(users)
    }

    @Test
    fun `사용할 수 있는 다른 닉네임일 때__변경하면__원래 입력으로 조회 중복 확인 갱신을 순서대로 한다`() {
        // given
        every { users.findNickname(any()) } returns Optional.of("Alice")
        every { users.existsByNickname(any()) } returns false
        justRun { users.updateNickname(any(), any()) }

        // when
        change.change(52L, "새Nick9")

        // then
        verifySequence {
            users.findNickname(52L)
            users.existsByNickname("새Nick9")
            users.updateNickname(52L, "새Nick9")
        }
    }

    @Test
    fun `다른 회원이 이미 쓰는 닉네임일 때__변경하면__중복으로 거부하고 갱신하지 않는다`() {
        // given
        every { users.findNickname(any()) } returns Optional.of("Alice")
        every { users.existsByNickname(any()) } returns true

        // when
        assertThrows(NicknameDuplicateException::class.java) { change.change(52L, "Taken") }

        // then
        verify(exactly = 1) { users.findNickname(52L) }
        verify(exactly = 1) { users.existsByNickname("Taken") }
        confirmVerified(users)
    }

    @Test
    fun `자기 행과 대소문자만 다른 닉네임일 때__변경하면__자기 자신을 중복 확인에서 제외하지 않는다`() {
        // given
        every { users.findNickname(any()) } returns Optional.of("Alice")
        every { users.existsByNickname(any()) } returns true

        // when
        assertThrows(NicknameDuplicateException::class.java) { change.change(52L, "alice") }

        // then
        verify(exactly = 1) { users.findNickname(52L) }
        verify(exactly = 1) { users.existsByNickname("alice") }
        confirmVerified(users)
    }

    @Test
    fun `사전 확인 뒤 쓰기에서 무결성 오류가 날 때__변경하면__사전 확인 중복으로 바꿔 쓰지 않고 그대로 전파한다`() {
        // given
        val failure = DataIntegrityViolationException("synthetic private database failure")

        every { users.findNickname(any()) } returns Optional.of("Alice")
        every { users.existsByNickname(any()) } returns false
        every { users.updateNickname(any(), any()) } throws failure

        // when
        val thrown = assertThrows(DataIntegrityViolationException::class.java) { change.change(52L, "NewNick") }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { users.updateNickname(52L, "NewNick") }
    }
}
