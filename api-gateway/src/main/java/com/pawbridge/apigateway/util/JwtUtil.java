package com.pawbridge.apigateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * API Gateway용 JWT 유틸리티
 * - Access Token 검증
 * - 토큰에서 사용자 정보 추출
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Access Token 검증
     */
    public boolean validateAccessToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * JWT 토큰에서 이메일 추출
     */
    public String getEmailFromToken(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * JWT 토큰에서 userId 추출
     */
    public Long getUserIdFromToken(String token) {
        return getClaims(token).get("userId", Long.class);
    }

    /**
     * JWT 토큰에서 사용자 이름 추출
     */
    public String getNameFromToken(String token) {
        return getClaims(token).get("name", String.class);
    }

    /**
     * JWT 토큰에서 role 추출
     */
    public String getRoleFromToken(String token) {
        return getClaims(token).get("role", String.class);
    }

    /**
     * JWT 토큰에서 careRegNo 추출
     * - ROLE_SHELTER인 경우에만 존재
     * @return careRegNo (없으면 null)
     */
    public String getCareRegNoFromToken(String token) {
        try {
            return getClaims(token).get("careRegNo", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JWT 토큰에서 Claims 추출
     */
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
