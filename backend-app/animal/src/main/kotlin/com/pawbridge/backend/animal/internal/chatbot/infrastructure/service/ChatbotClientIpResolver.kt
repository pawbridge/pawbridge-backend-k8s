package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import jakarta.servlet.http.HttpServletRequest
import java.util.regex.Pattern

class ChatbotClientIpResolver {

    fun resolve(request: HttpServletRequest): String {
        val cloudflareIp = firstNonBlank(request.getHeader("CF-Connecting-IP"))
        if (cloudflareIp != null) {
            return cloudflareIp
        }

        val forwardedFor = firstNonBlank(request.getHeader("X-Forwarded-For"))
        if (forwardedFor != null) {
            // Java String.split(","): trailing empty strings are dropped (",".split(",") is empty, so [0] throws).
            return javaTrim(COMMA.split(forwardedFor)[0])
        }

        val realIp = firstNonBlank(request.getHeader("X-Real-IP"))
        if (realIp != null) {
            return realIp
        }

        return if (request.remoteAddr != null) request.remoteAddr else "unknown"
    }

    private fun firstNonBlank(value: String?): String? {
        return if (value != null && !isBlank(value)) javaTrim(value) else null
    }

    private companion object {
        private val COMMA: Pattern = Pattern.compile(",")

        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        // Java String.trim() removes every character up to U+0020; Kotlin trim() uses Character.isWhitespace.
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }
    }
}
