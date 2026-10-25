package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import java.time.Duration
import java.util.UUID

class ChatbotCookieService {

    fun resolveAnonymousSessionId(request: HttpServletRequest, response: HttpServletResponse): String {
        val existingId = findCookieValue(request)
        if (existingId != null && !isBlank(existingId)) {
            return existingId
        }

        val newId = UUID.randomUUID().toString()
        val cookie = ResponseCookie.from(COOKIE_NAME, newId)
            .httpOnly(true)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .path("/")
            .maxAge(COOKIE_MAX_AGE)
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
        return newId
    }

    private fun findCookieValue(request: HttpServletRequest): String? {
        val cookies = request.cookies ?: return null
        return cookies.asSequence()
            .filter { cookie -> COOKIE_NAME == cookie.name }
            .map { cookie -> cookie.value }
            .firstOrNull { value -> isValidAnonymousSessionId(value) }
    }

    private fun isValidAnonymousSessionId(value: String?): Boolean {
        if (value == null || isBlank(value)) {
            return false
        }
        try {
            UUID.fromString(value)
            return true
        } catch (e: IllegalArgumentException) {
            return false
        }
    }

    private fun isSecureRequest(request: HttpServletRequest): Boolean {
        val forwardedProto = request.getHeader("X-Forwarded-Proto")
        return request.isSecure || "https".equals(forwardedProto, ignoreCase = true)
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    companion object {
        const val COOKIE_NAME: String = "pawbridge_anon_id"
        private val COOKIE_MAX_AGE: Duration = Duration.ofDays(180)
    }
}
