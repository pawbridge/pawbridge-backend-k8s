package com.pawbridge.backend.user.internal.session.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class RefreshTokensTest {
    @Test
    fun `보안 난수 생성기일 때__토큰을 생성하면__32바이트의 패딩 없는 base64url을 돌려준다`() {
        // given
        val tokens = RefreshTokens()
        val seen = HashSet<String>()

        // when
        val generated = (0 until 100).map { tokens.generate() }

        // then
        for (token in generated) {
            assertEquals(43, token.length)
            assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(token).size)
            assertTrue(seen.add(token))
        }
    }

    @Test
    fun `토큰이 주어졌을 때__해시하면__소문자 16진수 sha256을 돌려준다`() {
        // given
        val generated = RefreshTokens().generate()

        // when
        val abc = RefreshTokens.hash("abc")
        val hash = RefreshTokens.hash(generated)

        // then
        // SHA-256("abc") from FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(RefreshTokens.hash("abd"), RefreshTokens.hash("abc"))
    }

    @Test
    fun `이 클래스가 발급하지 않는 값일 때__형식을 검사하면__발급한 토큰만 통과시키고 나머지는 거부한다`() {
        // given
        val issued = RefreshTokens().generate()

        // when
        val results = listOf(
            RefreshTokens.wellFormed(issued),
            RefreshTokens.wellFormed(null),
            RefreshTokens.wellFormed(""),
            RefreshTokens.wellFormed("eyJhbGciOiJIUzI1NiJ9.e30.signature"),
            RefreshTokens.wellFormed("A".repeat(42) + "="),
            RefreshTokens.wellFormed("A".repeat(44)),
        )

        // then
        assertEquals(listOf(true, false, false, false, false, false), results)
    }
}
