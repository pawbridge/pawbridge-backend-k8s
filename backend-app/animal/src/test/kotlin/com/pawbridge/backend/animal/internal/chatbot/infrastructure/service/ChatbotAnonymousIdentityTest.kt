package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import java.util.stream.Stream

class ChatbotAnonymousIdentityTest {
    @ParameterizedTest
    @ValueSource(strings = ["01234567-89ab-cdef-0123-456789abcdef", "1-1-1-1-1"])
    fun `UUID로 해석되는 쿠키가 있을 때__익명 세션을 해석하면__재발급이나 정규화 없이 원래 값을 재사용한다`(
        anonymous: String,
    ) {
        // given
        val request = MockHttpServletRequest()
        request.setCookies(Cookie(ChatbotCookieService.COOKIE_NAME, anonymous))
        val response = MockHttpServletResponse()

        // when
        val resolved = ChatbotCookieService().resolveAnonymousSessionId(request, response)

        // then
        assertEquals(anonymous, resolved)
        assertNull(response.getHeader("Set-Cookie"))
    }

    @ParameterizedTest
    @MethodSource("newCookies")
    fun `쿠키가 없거나 잘못됐고 전송 방식이 다양할 때__익명 세션을 해석하면__180일짜리 UUID HttpOnly Lax 루트 쿠키를 발급한다`(
        value: String?,
        secure: Boolean,
        proto: String?,
        expectedSecure: Boolean,
    ) {
        // given
        val request = MockHttpServletRequest()
        if (value != null) {
            request.setCookies(Cookie(ChatbotCookieService.COOKIE_NAME, value))
        }
        request.isSecure = secure
        if (proto != null) {
            request.addHeader("X-Forwarded-Proto", proto)
        }
        val response = MockHttpServletResponse()

        // when
        val anonymous: String = ChatbotCookieService().resolveAnonymousSessionId(request, response)

        // then
        UUID.fromString(anonymous)
        val cookie: String? = response.getHeader("Set-Cookie")
        assertNotNull(cookie)
        assertTrue(cookie!!.startsWith("pawbridge_anon_id=" + anonymous))
        assertTrue(cookie.contains("Path=/"))
        assertTrue(cookie.contains("Max-Age=15552000"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Lax"))
        assertEquals(expectedSecure, cookie.contains("; Secure"))
    }

    @ParameterizedTest
    @MethodSource("ips")
    fun `원래의 프록시 헤더와 원격 주소가 있을 때__IP를 해석하면__CF와 전달 헤더와 실제 IP와 원격 주소의 우선순위를 유지한다`(
        cf: String?,
        forwarded: String?,
        real: String?,
        remote: String?,
        expected: String,
    ) {
        // given
        val request = mockk<HttpServletRequest>()

        every { request.getHeader("CF-Connecting-IP") } returns cf
        every { request.getHeader("X-Forwarded-For") } returns forwarded
        every { request.getHeader("X-Real-IP") } returns real
        every { request.remoteAddr } returns remote

        // when
        val resolved = ChatbotClientIpResolver().resolve(request)

        // then
        assertEquals(expected, resolved)
    }

    @Test
    fun `알려진 HMAC-SHA256 벡터가 있을 때__해시하면__원래의 소문자 16진수를 돌려준다`() {
        // given
        val hashes = ChatbotIpHashService("key")

        // when
        val hash = hashes.hash("The quick brown fox jumps over the lazy dog")

        // then
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", hash)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", " "])
    fun `해시 비밀값이 비어 있을 때__생성한 뒤 해시하면__요청 시점의 실패를 유지한다`(secret: String?) {
        // given
        val hashes = assertDoesNotThrow<ChatbotIpHashService> { ChatbotIpHashService(secret) }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { hashes.hash("192.0.2.1") }

        // then
        assertEquals("chatbot.security.ip-hash-secret 설정이 필요합니다.", thrown.message)
    }

    companion object {
        @JvmStatic
        fun newCookies(): Stream<Arguments> = Stream.of(
            Arguments.of(null, false, null, false),
            Arguments.of("broken", false, null, false),
            Arguments.of("", true, null, true),
            Arguments.of(" ", false, "HTTPS", true),
        )

        @JvmStatic
        fun ips(): Stream<Arguments> = Stream.of(
            Arguments.of(" 192.0.2.1 ", "192.0.2.2, 192.0.2.3", "192.0.2.4", "192.0.2.5", "192.0.2.1"),
            Arguments.of(" ", " 192.0.2.2, 192.0.2.3 ", "192.0.2.4", "192.0.2.5", "192.0.2.2"),
            Arguments.of(null, " ", " 192.0.2.4 ", "192.0.2.5", "192.0.2.4"),
            Arguments.of(null, null, null, "192.0.2.5", "192.0.2.5"),
            Arguments.of(null, null, null, null, "unknown"),
        )
    }
}
