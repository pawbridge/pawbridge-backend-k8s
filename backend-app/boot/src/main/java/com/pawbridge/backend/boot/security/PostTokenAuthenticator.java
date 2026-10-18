package com.pawbridge.backend.boot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import javax.crypto.SecretKey;

/** Verifies existing signed tokens; it does not issue tokens or query member state. */
public final class PostTokenAuthenticator {
    private final JwtParser parser;

    public PostTokenAuthenticator(SecretKey key) {
        parser = Jwts.parser().verifyWith(key).build();
    }

    public long authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new Failure("인증 토큰이 필요합니다.");
        Claims claims;
        try { claims = parser.parseSignedClaims(authorization.substring(7)).getPayload(); }
        catch (RuntimeException failure) { throw new Failure("유효하지 않거나 만료된 토큰입니다."); }
        try {
            Long userId = claims.get("userId", Long.class);
            String role = claims.get("role", String.class);
            if (userId == null || userId <= 0 || role == null) throw new IllegalArgumentException();
            return userId;
        } catch (RuntimeException failure) { throw new Failure("토큰 파싱에 실패했습니다."); }
    }

    public static final class Failure extends RuntimeException {
        private Failure(String message) { super(message); }
    }
}
