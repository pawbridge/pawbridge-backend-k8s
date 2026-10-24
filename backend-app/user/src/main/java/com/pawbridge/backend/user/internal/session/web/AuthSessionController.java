package com.pawbridge.backend.user.internal.session.web;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cookie-only refresh and logout. Both are reachable without an access token, so they only accept requests a
 * page on an allowed origin could have sent: an exact allowed Origin and a JSON content type, which a
 * cross-site form cannot produce without a CORS preflight.
 */
@RestController
public final class AuthSessionController {
    private final AuthSessions sessions;
    private final Set<String> allowedOrigins;

    public AuthSessionController(AuthSessions sessions, Collection<String> allowedOrigins) {
        this.sessions = sessions;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<Response<AccessToken>> refresh(HttpServletRequest request) {
        if (!fromAllowedPage(request)) {
            return forbidden();
        }
        String refreshToken = RefreshCookies.read(request).orElse(null);
        AuthSessions.RefreshResult result = sessions.refresh(refreshToken);
        var body = new Response<>(200, "토큰이 재발급되었습니다.", new AccessToken(result.accessToken()));
        var response = ResponseEntity.ok();
        if (result instanceof AuthSessions.Rotated rotated) {
            response.header(HttpHeaders.SET_COOKIE, RefreshCookies.issue(rotated.refreshToken()).toString());
        }
        return response.body(body);
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Response<Void>> logout(HttpServletRequest request) {
        if (!fromAllowedPage(request)) {
            return forbidden();
        }
        sessions.logout(RefreshCookies.read(request).orElse(null));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString())
                .body(new Response<>(200, "로그아웃되었습니다.", null));
    }

    private boolean fromAllowedPage(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !allowedOrigins.contains(origin)) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException malformed) {
            return false;
        }
    }

    private static <T> ResponseEntity<Response<T>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new Response<>(403, "접근할 수 없습니다.", null));
    }

    public record Response<T>(int code, String message, T data) { }

    public record AccessToken(String accessToken) {

        @Override
        public String toString() {
            return "AccessToken";
        }
    }
}
