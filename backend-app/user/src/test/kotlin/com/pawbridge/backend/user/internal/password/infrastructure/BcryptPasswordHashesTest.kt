package com.pawbridge.backend.user.internal.password.infrastructure

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class BcryptPasswordHashesTest {
    private val hashes = BcryptPasswordHashes()

    @Test
    fun `예전 기본 bcrypt 해시일 때__일치를 확인하면__원래의 평문 비밀번호만 일치한다`() {
        // given
        val legacy = BCryptPasswordEncoder().encode("Legacy12!")

        // when
        val original = hashes.matches("Legacy12!", legacy)
        val changedCase = hashes.matches("legacy12!", legacy)

        // then
        assertTrue(original)
        assertFalse(changedCase)
    }

    @Test
    fun `새 비밀번호일 때__인코딩하면__기본 강도의 솔트 bcrypt를 예전 인코더로 검증할 수 있다`() {
        // given
        // when
        val first = hashes.encode("NewPass1!")!!
        val second = hashes.encode("NewPass1!")

        // then
        assertTrue(first.startsWith("\$2a\$10\$"))
        assertNotEquals(first, second)
        assertTrue(BCryptPasswordEncoder().matches("NewPass1!", first))
    }

    @Test
    fun `저장된 값이 없거나 bcrypt가 아닐 때__일치를 확인하면__실패 대신 불일치를 알린다`() {
        // given
        // when
        val missing = hashes.matches("NewPass1!", null)
        val empty = hashes.matches("NewPass1!", "")
        val plainText = hashes.matches("NewPass1!", "plain-text")

        // then
        assertFalse(missing)
        assertFalse(empty)
        assertFalse(plainText)
    }
}
