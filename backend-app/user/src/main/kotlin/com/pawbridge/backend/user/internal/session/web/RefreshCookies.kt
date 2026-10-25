package com.pawbridge.backend.user.internal.session.web

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import java.time.Duration
import java.util.Optional

/**
 * The refresh-token cookie. The __Secure- prefix makes browsers refuse it without Secure; no Domain attribute
 * keeps it on the API host only, and the path limits it to the refresh and logout endpoints.
 *
 * <p>SameSite=Lax rather than Strict: some WebKit and in-app browsers drop a Strict cookie set during the
 * cross-site social login callback. Lax still withholds it from cross-site POSTs, and both endpoints are
 * POST-only and additionally require an exact allowed Origin and a JSON content type.
 */
object RefreshCookies {
    const val NAME = "__Secure-pb_refresh"
    const val PATH = "/api/v1/auth"

    @JvmStatic
    fun issue(token: IssuedRefreshToken): ResponseCookie =
        base(token.value)
            .maxAge(Duration.ofSeconds(maxOf(0, token.maxAgeSeconds)))
            .build()

    @JvmStatic
    fun clear(): ResponseCookie =
        base("")
            .maxAge(Duration.ZERO)
            .build()

    @JvmStatic
    fun read(request: HttpServletRequest): Optional<String> {
        val cookies = request.cookies ?: return Optional.empty()
        for (cookie in cookies) {
            if (NAME == cookie.name && cookie.value != null && !cookie.value.isEmpty()) {
                return Optional.of(cookie.value)
            }
        }
        return Optional.empty()
    }

    private fun base(value: String?): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(NAME, value)
            .path(PATH)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
}
