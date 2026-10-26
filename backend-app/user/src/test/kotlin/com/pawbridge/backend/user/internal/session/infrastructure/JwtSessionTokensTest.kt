package com.pawbridge.backend.user.internal.session.infrastructure

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

class JwtSessionTokensTest {
    @Test
    fun `기존 클레임과 같은 서명 키일 때__액세스 토큰을 발급하면__nonce 없이 역할과 보호소 등록번호를 담고 공백 등록번호는 생략한다`() {
        // given
        val tokens = JwtSessionTokens(KEY, 600_000)

        // when
        val claims = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(
            tokens.access(JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_USER", "CARE")),
        ).payload
        val blank = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(
            tokens.access(JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_SHELTER", " ")),
        ).payload

        // then
        assertEquals(setOf("sub", "userId", "name", "role", "careRegNo", "iat", "exp"), claims.keys)
        assertEquals(7L, claims.get("userId", Long::class.javaObjectType))
        assertEquals("ROLE_USER", claims.get("role", String::class.java))
        assertEquals("CARE", claims.get("careRegNo", String::class.java))
        assertEquals(600_000L, claims.expiration.time - claims.issuedAt.time)
        assertFalse(blank.containsKey("careRegNo"))
    }

    companion object {
        private val KEY: SecretKey = Keys.hmacShaKeyFor(
            "session-synthetic-only-key-at-least-sixty-four-characters-0123456789".toByteArray(StandardCharsets.UTF_8),
        )
    }
}
