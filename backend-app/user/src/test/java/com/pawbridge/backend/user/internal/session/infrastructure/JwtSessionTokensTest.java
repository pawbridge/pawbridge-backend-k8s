package com.pawbridge.backend.user.internal.session.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtSessionTokensTest {
    private static final javax.crypto.SecretKey KEY=Keys.hmacShaKeyFor("session-synthetic-only-key-at-least-sixty-four-characters-0123456789".getBytes(StandardCharsets.UTF_8));
    @Test void given__existing_claims_and_same_key__when__issuing__then__keep_roles_care_and_original_jwt_shapes_without_nonce() {
        var tokens=new JwtSessionTokens(KEY,600_000,1_200_000);
        var claims=Jwts.parser().verifyWith(KEY).build().parseSignedClaims(tokens.access(
                new JwtSessionTokens.UserClaims(7,"synthetic@fixture.invalid","Synthetic","ROLE_USER","CARE"))).getPayload();
        assertEquals(Set.of("sub","userId","name","role","careRegNo","iat","exp"),claims.keySet());
        assertEquals(7,claims.get("userId",Long.class));assertEquals("ROLE_USER",claims.get("role",String.class));
        assertEquals("CARE",claims.get("careRegNo",String.class));assertEquals(600_000,claims.getExpiration().getTime()-claims.getIssuedAt().getTime());
        var refresh=Jwts.parser().verifyWith(KEY).build().parseSignedClaims(tokens.refresh()).getPayload();
        assertEquals(Set.of("iat","exp"),refresh.keySet());assertEquals(1_200_000,refresh.getExpiration().getTime()-refresh.getIssuedAt().getTime());
        var blank=Jwts.parser().verifyWith(KEY).build().parseSignedClaims(tokens.access(
                new JwtSessionTokens.UserClaims(7,"synthetic@fixture.invalid","Synthetic","ROLE_SHELTER"," "))).getPayload();
        assertFalse(blank.containsKey("careRegNo"));assertEquals(1_200_000,tokens.refreshExpiration());
    }
    @Test void given__signature_claims_without_access_identity__when__validating_refresh__then__preserve_parser_only_semantics_and_reject_invalid_or_expired() {
        var tokens=new JwtSessionTokens(KEY,600_000,600_000);
        assertTrue(tokens.valid(tokens.refresh()));
        assertTrue(tokens.valid(Jwts.builder().claim("anything","synthetic").signWith(KEY).compact()));
        assertFalse(tokens.valid("invalid"));assertFalse(tokens.valid(null));
        assertFalse(tokens.valid(Jwts.builder().expiration(new Date(System.currentTimeMillis()-60_000)).signWith(KEY).compact()));
        var other=Keys.hmacShaKeyFor("different-synthetic-key-at-least-sixty-four-characters-01234567890123".getBytes(StandardCharsets.UTF_8));
        assertFalse(tokens.valid(Jwts.builder().expiration(new Date(System.currentTimeMillis()+600_000)).signWith(other).compact()));
    }
}
