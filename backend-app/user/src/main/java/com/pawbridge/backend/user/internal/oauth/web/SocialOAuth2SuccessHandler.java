package com.pawbridge.backend.user.internal.oauth.web;

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuthPrincipal;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.web.RefreshCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sets the refresh cookie on the redirect and sends the browser to the frontend callback with no tokens in the
 * URL, so tokens never reach browser history, proxy logs or Referer headers. The frontend then calls refresh.
 */
public final class SocialOAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SocialOAuth2SuccessHandler.class);
    private final SocialLogin login;
    private final String redirectUri;

    public SocialOAuth2SuccessHandler(SocialLogin login, String redirectUri) {
        this.login = login;
        this.redirectUri = redirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            var principal = (SocialOAuthPrincipal) authentication.getPrincipal();
            IssuedRefreshToken refreshToken = login.complete(principal.user());
            response.addHeader(HttpHeaders.SET_COOKIE, RefreshCookies.issue(refreshToken).toString());
            response.sendRedirect(response.encodeRedirectURL(redirectUri));
        } catch (Exception failure) {
            LOG.error("OAuth2 token completion failed; exceptionType={}", failure.getClass().getName());
            String message = URLEncoder.encode("로그인 처리 중 오류가 발생했습니다", StandardCharsets.UTF_8);
            String target = UriComponentsBuilder.fromUriString(redirectUri.replace("/oauth/callback", "/login"))
                    .queryParam("error", message)
                    .build()
                    .toUriString();
            response.sendRedirect(response.encodeRedirectURL(target));
        }
    }
}
