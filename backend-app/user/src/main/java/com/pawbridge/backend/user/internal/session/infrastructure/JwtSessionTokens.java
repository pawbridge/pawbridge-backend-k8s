package com.pawbridge.backend.user.internal.session.infrastructure;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.util.Date;
import javax.crypto.SecretKey;

/** The existing issuer's claim/signature/time behavior, without a legacy JPA dependency. */
public class JwtSessionTokens {
    private final SecretKey key;
    private final long accessExpiration;
    private final long refreshExpiration;
    private final JwtParser parser;

    public JwtSessionTokens(SecretKey key, long accessExpiration, long refreshExpiration) {
        this.key=key; this.accessExpiration=accessExpiration; this.refreshExpiration=refreshExpiration;
        this.parser=Jwts.parser().verifyWith(key).build();
    }
    public boolean valid(String token) {
        try { parser.parseSignedClaims(token).getPayload(); return true; }
        catch(Exception failure) { return false; }
    }
    public String access(UserClaims user) {
        Date now=new Date();
        var builder=Jwts.builder().subject(user.email()).claim("userId",user.userId())
                .claim("name",user.name()).claim("role",user.role());
        if(user.careRegNo()!=null && !user.careRegNo().isBlank()) builder.claim("careRegNo",user.careRegNo());
        return builder.issuedAt(now).expiration(new Date(now.getTime()+accessExpiration)).signWith(key).compact();
    }
    public String refresh() {
        Date now=new Date();
        return Jwts.builder().issuedAt(now).expiration(new Date(now.getTime()+refreshExpiration)).signWith(key).compact();
    }
    public long refreshExpiration() { return refreshExpiration; }
    public record UserClaims(long userId, String email, String name, String role, String careRegNo) {}
}
