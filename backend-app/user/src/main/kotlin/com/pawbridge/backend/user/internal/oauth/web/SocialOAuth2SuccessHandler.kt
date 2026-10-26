package com.pawbridge.backend.user.internal.oauth.web

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuthPrincipal
import com.pawbridge.backend.user.internal.session.web.RefreshCookies
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Sets the refresh cookie on the redirect and sends the browser to the frontend callback with no tokens in the
 * URL, so tokens never reach browser history, proxy logs or Referer headers. The frontend then calls refresh.
 */
class SocialOAuth2SuccessHandler(
    private val login: SocialLogin,
    private val redirectUri: String,
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        try {
            val principal = authentication.principal as SocialOAuthPrincipal
            val refreshToken = login.complete(principal.user())
            response.addHeader(HttpHeaders.SET_COOKIE, RefreshCookies.issue(refreshToken).toString())
            response.sendRedirect(response.encodeRedirectURL(redirectUri))
        } catch (failure: Exception) {
            LOG.error("OAuth2 token completion failed; exceptionType={}", failure.javaClass.name)
            val message = URLEncoder.encode("로그인 처리 중 오류가 발생했습니다", StandardCharsets.UTF_8)
            val target = UriComponentsBuilder.fromUriString(redirectUri.replace("/oauth/callback", "/login"))
                .queryParam("error", message)
                .build()
                .toUriString()
            response.sendRedirect(response.encodeRedirectURL(target))
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(SocialOAuth2SuccessHandler::class.java)
    }
}
