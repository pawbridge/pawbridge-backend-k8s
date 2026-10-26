package com.pawbridge.backend.boot.security

import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import java.util.Date
import javax.crypto.SecretKey
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** Verifies existing signed tokens; it does not issue tokens or query member state. */
class PostTokenAuthenticator(key: SecretKey) {
    private val parser: JwtParser = Jwts.parser().verifyWith(key).build()

    fun authenticate(authorization: String?): Long {
        return authenticateToken(authorization).principal as Long
    }

    fun authenticateToken(authorization: String?): UsernamePasswordAuthenticationToken {
        return authenticateToken(authorization, false)
    }

    fun authenticateToken(authorization: String?, requireExpiry: Boolean): UsernamePasswordAuthenticationToken {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw Failure("인증 토큰이 필요합니다.")
        }
        val claims = try {
            parser.parseSignedClaims(authorization.substring(7)).payload
        } catch (failure: RuntimeException) {
            throw Failure("유효하지 않거나 만료된 토큰입니다.")
        }
        try {
            val userId = claims.get("userId", Long::class.javaObjectType)
            val role = claims.get("role", String::class.java)
            if (userId == null || userId <= 0 || role == null) {
                throw IllegalArgumentException()
            }
            val expiration: Date? = claims.expiration
            if (requireExpiry && expiration == null) {
                throw IllegalArgumentException()
            }
            val authorities: List<SimpleGrantedAuthority> = if ("ROLE_ADMIN" == role) {
                listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
            } else {
                emptyList()
            }
            val authentication = UsernamePasswordAuthenticationToken(userId, null, authorities)
            authentication.details = expiration?.toInstant()
            return authentication
        } catch (failure: RuntimeException) {
            throw Failure("토큰 파싱에 실패했습니다.")
        }
    }

    class Failure internal constructor(message: String) : RuntimeException(message)
}
