package com.pawbridge.backend.user.internal.session.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    @Test
    void given__the_secure_random_generator__when__generating__then__return_unpadded_base64url_of_thirty_two_bytes() {
        var tokens = new RefreshTokens();
        var seen = new HashSet<String>();

        for (int index = 0; index < 100; index++) {
            String token = tokens.generate();
            assertEquals(43, token.length());
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(32, Base64.getUrlDecoder().decode(token).length);
            assertTrue(seen.add(token));
        }
    }

    @Test
    void given__a_token__when__hashing__then__return_lowercase_hex_sha256() {
        // SHA-256("abc") from FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RefreshTokens.hash("abc"));
        String hash = RefreshTokens.hash(new RefreshTokens().generate());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertNotEquals(RefreshTokens.hash("abd"), RefreshTokens.hash("abc"));
    }

    @Test
    void given__values_this_class_never_issues__when__checking_the_format__then__reject_them() {
        assertTrue(RefreshTokens.wellFormed(new RefreshTokens().generate()));
        assertFalse(RefreshTokens.wellFormed(null));
        assertFalse(RefreshTokens.wellFormed(""));
        assertFalse(RefreshTokens.wellFormed("eyJhbGciOiJIUzI1NiJ9.e30.signature"));
        assertFalse(RefreshTokens.wellFormed("A".repeat(42) + "="));
        assertFalse(RefreshTokens.wellFormed("A".repeat(44)));
    }
}
