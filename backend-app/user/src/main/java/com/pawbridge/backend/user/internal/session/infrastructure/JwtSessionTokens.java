package com.pawbridge.backend.user.internal.session.infrastructure;

import io.jsonwebtoken.Jwts;
import java.util.Date;
import javax.crypto.SecretKey;

/** The existing issuer's access-token claim/signature/time behavior, without a legacy JPA dependency. */
public class JwtSessionTokens {
    private final SecretKey key;
    private final long accessExpiration;

    public JwtSessionTokens(SecretKey key, long accessExpiration) {
        this.key = key;
        this.accessExpiration = accessExpiration;
    }

    public String access(UserClaims user) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(user.email())
                .claim("userId", user.userId())
                .claim("name", user.name())
                .claim("role", user.role());
        if (user.careRegNo() != null && !user.careRegNo().isBlank()) {
            builder.claim("careRegNo", user.careRegNo());
        }
        return builder.issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpiration))
                .signWith(key)
                .compact();
    }

    public record UserClaims(long userId, String email, String name, String role, String careRegNo) { }
}
