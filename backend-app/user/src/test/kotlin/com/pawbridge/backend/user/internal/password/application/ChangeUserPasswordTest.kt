package com.pawbridge.backend.user.internal.password.application

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

class ChangeUserPasswordTest {
    private val users = mockk<UserPasswordStore>()
    private val hashes = mockk<PasswordHashes>()
    private val change = ChangeUserPassword(users, hashes)

    @Test
    fun `현재 비밀번호가 일치하는 로컬 회원일 때__변경하면__조회 일치 확인 인코딩 갱신을 원래 순서로 수행한다`() {
        // given
        every { users.findCredentials(any()) } returns Optional.of(StoredCredentials("LOCAL", STORED))
        every { hashes.matches(any(), any()) } returns true
        every { hashes.encode(any()) } returns "\$2a\$10\$synthetic.new.hash"
        justRun { users.updatePassword(any(), any()) }

        // when
        change.change(52L, "Current1!", "NewPass1!")

        // then
        verifySequence {
            users.findCredentials(52L)
            hashes.matches("Current1!", STORED)
            hashes.encode("NewPass1!")
            users.updatePassword(52L, "\$2a\$10\$synthetic.new.hash")
        }
    }

    @Test
    fun `회원이 없을 때__변경하면__비밀번호 확인과 갱신보다 먼저 회원 없음으로 실패한다`() {
        // given
        every { users.findCredentials(any()) } returns Optional.empty()

        // when
        assertThrows(UserNotFoundException::class.java) { change.change(52L, "Current1!", "NewPass1!") }

        // then
        verify(exactly = 1) { users.findCredentials(52L) }
        confirmVerified(users)
        verify { hashes wasNot Called }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["GOOGLE", "NAVER", "local", ""])
    fun `로컬이 아닌 제공자일 때__변경하면__비밀번호 일치 확인보다 먼저 소셜 회원 거부로 실패한다`(provider: String?) {
        // given
        every { users.findCredentials(any()) } returns Optional.of(StoredCredentials(provider, null))

        // when
        assertThrows(OAuthUserCannotChangePasswordException::class.java) { change.change(52L, "Current1!", "NewPass1!") }

        // then
        verify(exactly = 1) { users.findCredentials(52L) }
        confirmVerified(users)
        verify { hashes wasNot Called }
    }

    @Test
    fun `현재 비밀번호가 틀리고 새 비밀번호가 같아도__변경하면__현재 비밀번호 불일치가 우선하고 아무것도 쓰지 않는다`() {
        // given
        every { users.findCredentials(any()) } returns Optional.of(StoredCredentials("LOCAL", STORED))
        every { hashes.matches(any(), any()) } returns false

        // when
        assertThrows(InvalidCurrentPasswordException::class.java) { change.change(52L, "Wrong1!", "Wrong1!") }

        // then
        verify(exactly = 1) { users.findCredentials(52L) }
        verify(exactly = 1) { hashes.matches("Wrong1!", STORED) }
        confirmVerified(users, hashes)
    }

    @Test
    fun `새 비밀번호가 일치하는 현재 비밀번호와 같을 때__변경하면__인코딩 전에 같은 비밀번호로 거부한다`() {
        // given
        every { users.findCredentials(any()) } returns Optional.of(StoredCredentials("LOCAL", STORED))
        every { hashes.matches(any(), any()) } returns true

        // when
        assertThrows(SamePasswordException::class.java) { change.change(52L, "Same1234!", "Same1234!") }

        // then
        verify(exactly = 1) { users.findCredentials(52L) }
        verify(exactly = 1) { hashes.matches("Same1234!", STORED) }
        confirmVerified(users, hashes)
    }

    @Test
    fun `쓰기가 실패했을 때__변경하면__실패를 그대로 전파한다`() {
        // given
        val failure = DataIntegrityViolationException("synthetic private database failure")

        every { users.findCredentials(any()) } returns Optional.of(StoredCredentials("LOCAL", STORED))
        every { hashes.matches(any(), any()) } returns true
        every { hashes.encode(any()) } returns "\$2a\$10\$synthetic.new.hash"
        every { users.updatePassword(any(), any()) } throws failure

        // when
        val thrown = assertThrows(DataIntegrityViolationException::class.java) { change.change(52L, "Current1!", "NewPass1!") }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { users.updatePassword(52L, "\$2a\$10\$synthetic.new.hash") }
    }

    @Test
    fun `저장된 자격 증명일 때__문자열로 표현하면__해시를 가린다`() {
        // given
        val credentials = StoredCredentials("LOCAL", STORED)

        // when
        val rendered = credentials.toString()

        // then
        assertTrue(rendered.contains("LOCAL"))
        assertFalse(rendered.contains(STORED))
    }

    companion object {
        private const val STORED = "\$2a\$10\$synthetic.stored.hash.value.for.unit.tests.only"
    }
}
