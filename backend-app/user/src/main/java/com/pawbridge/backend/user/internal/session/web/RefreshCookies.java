package com.pawbridge.backend.user.internal.session.web;

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * The refresh-token cookie. The __Secure- prefix makes browsers refuse it without Secure; no Domain attribute
 * keeps it on the API host only, and the path limits it to the refresh and logout endpoints.
 */
public final class RefreshCookies {
    public static final String NAME = "__Secure-pb_refresh";
    public static final String PATH = "/api/v1/auth";

    private RefreshCookies() { }

    public static ResponseCookie issue(IssuedRefreshToken token) {
        return base(token.value())
                .maxAge(Duration.ofSeconds(Math.max(0, token.maxAgeSeconds())))
                .build();
    }

    public static ResponseCookie clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build();
    }

    public static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .path(PATH)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict");
    }
}
