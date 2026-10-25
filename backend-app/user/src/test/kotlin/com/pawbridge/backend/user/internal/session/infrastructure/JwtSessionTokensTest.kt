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
    fun given__existing_claims_and_same_key__when__issuing__then__keep_roles_care_and_original_jwt_shapes_without_nonce() {
        val tokens = JwtSessionTokens(KEY, 600_000)
        val claims = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(
            tokens.access(JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_USER", "CARE")),
        ).payload

        assertEquals(setOf("sub", "userId", "name", "role", "careRegNo", "iat", "exp"), claims.keys)
        assertEquals(7L, claims.get("userId", Long::class.javaObjectType))
        assertEquals("ROLE_USER", claims.get("role", String::class.java))
        assertEquals("CARE", claims.get("careRegNo", String::class.java))
        assertEquals(600_000L, claims.expiration.time - claims.issuedAt.time)
        val blank = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(
            tokens.access(JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_SHELTER", " ")),
        ).payload
        assertFalse(blank.containsKey("careRegNo"))
    }

    companion object {
        private val KEY: SecretKey = Keys.hmacShaKeyFor(
            "session-synthetic-only-key-at-least-sixty-four-characters-0123456789".toByteArray(StandardCharsets.UTF_8),
        )
    }
}
