package com.pawbridge.backend.user.internal.session.web

import com.pawbridge.backend.user.internal.session.application.AuthSessions
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cookie-only refresh and logout. Both are reachable without an access token, so they only accept requests a
 * page on an allowed origin could have sent: an exact allowed Origin and a JSON content type, which a
 * cross-site form cannot produce without a CORS preflight.
 */
@RestController
class AuthSessionController(
    private val sessions: AuthSessions,
    allowedOrigins: Collection<String>,
) {
    // Set.copyOf keeps rejecting null elements and a null collection with NullPointerException, as before.
    private val allowedOrigins: Set<String> = java.util.Set.copyOf(allowedOrigins)

    @PostMapping("/api/v1/auth/refresh")
    fun refresh(request: HttpServletRequest): ResponseEntity<Response<AccessToken>> {
        if (!fromAllowedPage(request)) {
            return forbidden()
        }
        val refreshToken = RefreshCookies.read(request).orElse(null)
        val result = sessions.refresh(refreshToken)
        val body = Response(200, "토큰이 재발급되었습니다.", AccessToken(result.accessToken()))
        val response = ResponseEntity.ok()
        if (result is AuthSessions.Rotated) {
            response.header(HttpHeaders.SET_COOKIE, RefreshCookies.issue(result.refreshToken!!).toString())
        }
        return response.body(body)
    }

    @PostMapping("/api/v1/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Response<Void>> {
        if (!fromAllowedPage(request)) {
            return forbidden()
        }
        sessions.logout(RefreshCookies.read(request).orElse(null))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString())
            .body(Response<Void>(200, "로그아웃되었습니다.", null))
    }

    private fun fromAllowedPage(request: HttpServletRequest): Boolean {
        val origin = request.getHeader(HttpHeaders.ORIGIN)
        if (origin == null || !allowedOrigins.contains(origin)) {
            return false
        }
        val contentType = request.contentType ?: return false
        return try {
            MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType))
        } catch (malformed: InvalidMediaTypeException) {
            false
        }
    }

    private fun <T> forbidden(): ResponseEntity<Response<T>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Response<T>(403, "접근할 수 없습니다.", null))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )

    @JvmRecord
    data class AccessToken(val accessToken: String?) {
        override fun toString(): String {
            return "AccessToken"
        }
    }
}
