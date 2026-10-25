package com.pawbridge.backend.user.internal.session.web

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Optional

class RefreshCookiesTest {
    @Test
    fun `발급된 토큰이 있을 때__쿠키를 만들면__호스트 전용 secure lax이고 auth 경로로 제한된다`() {
        // given
        val issued = IssuedRefreshToken("synthetic-value", 1_209_600)

        // when
        val cookie = RefreshCookies.issue(issued)

        // then
        assertEquals("__Secure-pb_refresh", cookie.name)
        assertEquals("synthetic-value", cookie.value)
        assertEquals("/api/v1/auth", cookie.path)
        assertEquals(null, cookie.domain)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecure)
        assertEquals("Lax", cookie.sameSite)
        assertEquals(Duration.ofSeconds(1_209_600), cookie.maxAge)
        assertTrue(cookie.toString().startsWith("__Secure-pb_refresh=synthetic-value; Path=/api/v1/auth; Max-Age=1209600; "))
    }

    @Test
    fun `로그아웃이나 거부 상황일 때__쿠키를 지우면__같은 속성에 최대 수명 0인 쿠키를 만든다`() {
        // when
        val cookie = RefreshCookies.clear()

        // then
        assertEquals("__Secure-pb_refresh", cookie.name)
        assertEquals("", cookie.value)
        assertEquals("/api/v1/auth", cookie.path)
        assertEquals(null, cookie.domain)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecure)
        assertEquals("Lax", cookie.sameSite)
        assertEquals(Duration.ZERO, cookie.maxAge)
        assertTrue(cookie.toString().contains("Max-Age=0"))
    }

    @Test
    fun `요청 쿠키가 없거나 비어 있거나 정상일 때__읽으면__비어 있지 않은 리프레시 쿠키만 돌려준다`() {
        // given
        val request = mockk<HttpServletRequest>()
        val emptyRefresh = arrayOf(Cookie("other", "x"), Cookie("__Secure-pb_refresh", ""))
        val validRefresh = arrayOf(Cookie("other", "x"), Cookie("__Secure-pb_refresh", "value"))

        every { request.cookies } returnsMany listOf(null, emptyRefresh, validRefresh)

        // when
        val withoutCookies = RefreshCookies.read(request)
        val withEmptyRefresh = RefreshCookies.read(request)
        val withValidRefresh = RefreshCookies.read(request)

        // then
        assertEquals(Optional.empty<String>(), withoutCookies)
        assertEquals(Optional.empty<String>(), withEmptyRefresh)
        assertEquals(Optional.of("value"), withValidRefresh)
        verify(exactly = 3) { request.cookies }
    }
}
