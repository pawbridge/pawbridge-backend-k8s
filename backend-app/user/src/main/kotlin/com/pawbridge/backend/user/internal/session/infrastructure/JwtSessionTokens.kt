package com.pawbridge.backend.user.internal.session.infrastructure

import io.jsonwebtoken.Jwts
import java.util.Date
import javax.crypto.SecretKey

/** The existing issuer's access-token claim/signature/time behavior, without a legacy JPA dependency. */
// Open like the Java class: it was not final, so it can still be subclassed or spied.
open class JwtSessionTokens(
    private val key: SecretKey,
    private val accessExpiration: Long,
) {

    open fun access(user: UserClaims): String {
        val now = Date()
        val builder = Jwts.builder()
            .subject(user.email)
            .claim("userId", user.userId)
            .claim("name", user.name)
            .claim("role", user.role)
        if (user.careRegNo != null && !user.careRegNo.isBlank()) {
            builder.claim("careRegNo", user.careRegNo)
        }
        return builder.issuedAt(now)
            .expiration(Date(now.time + accessExpiration))
            .signWith(key)
            .compact()
    }

    @JvmRecord
    data class UserClaims(
        val userId: Long,
        val email: String?,
        val name: String?,
        val role: String?,
        val careRegNo: String?,
    )
}
