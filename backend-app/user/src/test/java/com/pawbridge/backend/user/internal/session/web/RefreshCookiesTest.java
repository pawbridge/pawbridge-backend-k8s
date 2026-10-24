package com.pawbridge.backend.user.internal.session.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RefreshCookiesTest {

    @Test
    void given__an_issued_token__when__building_the_cookie__then__host_only_secure_strict_and_scoped_to_auth() {
        var cookie = RefreshCookies.issue(new IssuedRefreshToken("synthetic-value", 1_209_600));

        assertEquals("__Secure-pb_refresh", cookie.getName());
        assertEquals("synthetic-value", cookie.getValue());
        assertEquals("/api/v1/auth", cookie.getPath());
        assertEquals(null, cookie.getDomain());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals(Duration.ofSeconds(1_209_600), cookie.getMaxAge());
        assertTrue(cookie.toString().startsWith("__Secure-pb_refresh=synthetic-value; Path=/api/v1/auth; Max-Age=1209600; "));
    }

    @Test
    void given__a_logout_or_rejection__when__clearing__then__same_attributes_with_zero_max_age() {
        var cookie = RefreshCookies.clear();

        assertEquals("__Secure-pb_refresh", cookie.getName());
        assertEquals("", cookie.getValue());
        assertEquals("/api/v1/auth", cookie.getPath());
        assertEquals(null, cookie.getDomain());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals(Duration.ZERO, cookie.getMaxAge());
        assertTrue(cookie.toString().contains("Max-Age=0"));
    }

    @Test
    void given__request_cookies__when__reading__then__return_only_a_non_empty_refresh_cookie() {
        var request = mock(HttpServletRequest.class);

        when(request.getCookies()).thenReturn(null);
        assertEquals(Optional.empty(), RefreshCookies.read(request));

        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("other", "x"), new Cookie("__Secure-pb_refresh", "")});
        assertEquals(Optional.empty(), RefreshCookies.read(request));

        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("other", "x"), new Cookie("__Secure-pb_refresh", "value")});
        assertEquals(Optional.of("value"), RefreshCookies.read(request));
    }
}
