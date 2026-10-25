package com.pawbridge.backend.user.internal.session.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class RefreshTokensTest {
    @Test
    fun given__the_secure_random_generator__when__generating__then__return_unpadded_base64url_of_thirty_two_bytes() {
        val tokens = RefreshTokens()
        val seen = HashSet<String>()
        for (index in 0 until 100) {
            val token = tokens.generate()
            assertEquals(43, token.length)
            assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(token).size)
            assertTrue(seen.add(token))
        }
    }

    @Test
    fun given__a_token__when__hashing__then__return_lowercase_hex_sha256() {
        // SHA-256("abc") from FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RefreshTokens.hash("abc"))
        val hash = RefreshTokens.hash(RefreshTokens().generate())
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(RefreshTokens.hash("abd"), RefreshTokens.hash("abc"))
    }

    @Test
    fun given__values_this_class_never_issues__when__checking_the_format__then__reject_them() {
        assertTrue(RefreshTokens.wellFormed(RefreshTokens().generate()))
        assertFalse(RefreshTokens.wellFormed(null))
        assertFalse(RefreshTokens.wellFormed(""))
        assertFalse(RefreshTokens.wellFormed("eyJhbGciOiJIUzI1NiJ9.e30.signature"))
        assertFalse(RefreshTokens.wellFormed("A".repeat(42) + "="))
        assertFalse(RefreshTokens.wellFormed("A".repeat(44)))
    }
}
