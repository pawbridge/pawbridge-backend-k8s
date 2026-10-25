package com.pawbridge.backend.user.internal.session.web

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.Optional

class RefreshCookiesTest {
    @Test
    fun given__an_issued_token__when__building_the_cookie__then__host_only_secure_lax_and_scoped_to_auth() {
        val cookie = RefreshCookies.issue(IssuedRefreshToken("synthetic-value", 1_209_600))

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
    fun given__a_logout_or_rejection__when__clearing__then__same_attributes_with_zero_max_age() {
        val cookie = RefreshCookies.clear()

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
    fun given__request_cookies__when__reading__then__return_only_a_non_empty_refresh_cookie() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.cookies).thenReturn(null)
        assertEquals(Optional.empty<String>(), RefreshCookies.read(request))
        `when`(request.cookies).thenReturn(arrayOf(Cookie("other", "x"), Cookie("__Secure-pb_refresh", "")))
        assertEquals(Optional.empty<String>(), RefreshCookies.read(request))
        `when`(request.cookies).thenReturn(arrayOf(Cookie("other", "x"), Cookie("__Secure-pb_refresh", "value")))
        assertEquals(Optional.of("value"), RefreshCookies.read(request))
    }
}
