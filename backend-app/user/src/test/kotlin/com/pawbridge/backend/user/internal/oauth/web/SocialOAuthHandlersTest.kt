package com.pawbridge.backend.user.internal.oauth.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuthPrincipal
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.web.DefaultRedirectStrategy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SocialOAuthHandlersTest {
    private fun response(): HttpServletResponse {
        val r = mock(HttpServletResponse::class.java)
        `when`(r.encodeRedirectURL(anyString())).thenAnswer { call -> call.getArgument<String>(0) }
        return r
    }

    @Test
    fun given__persisted_social_user__when__success_completes__then__redirect_without_tokens_and_set_the_refresh_cookie() {
        val login = mock(SocialLogin::class.java)
        val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")
        `when`(login.complete(user)).thenReturn(IssuedRefreshToken(SYNTHETIC_REFRESH, 1_209_600))
        val response = response()
        val redirect = LoggerFactory.getLogger(DefaultRedirectStrategy::class.java) as Logger
        val previous = redirect.level
        val events = ListAppender<ILoggingEvent>()
        events.start()
        redirect.addAppender(events)
        redirect.level = Level.DEBUG
        try {
            SocialOAuth2SuccessHandler(login, FRONT).onAuthenticationSuccess(
                mock(HttpServletRequest::class.java),
                response,
                UsernamePasswordAuthenticationToken(SocialOAuthPrincipal(user, mapOf()), null),
            )

            val url = ArgumentCaptor.forClass(String::class.java)
            verify(response).sendRedirect(url.capture())
            assertEquals(FRONT, url.value)
            assertNull(URI.create(url.value).rawQuery)
            val cookie = ArgumentCaptor.forClass(String::class.java)
            verify(response).addHeader(eq("Set-Cookie"), cookie.capture())
            assertEquals(
                "__Secure-pb_refresh=" + SYNTHETIC_REFRESH + "; Path=/api/v1/auth; Max-Age=1209600; " +
                    "Expires=" + cookieExpiry(cookie.value) + "; Secure; HttpOnly; SameSite=Lax",
                cookie.value,
            )
            assertFalse(cookie.value.contains("Domain="))
            verify(login).complete(user)
            for (event in events.list) {
                assertFalse(event.formattedMessage.contains(SYNTHETIC_REFRESH))
            }
        } finally {
            redirect.level = previous
            redirect.detachAppender(events)
            events.stop()
        }
    }

    private fun cookieExpiry(header: String): String {
        val start = header.indexOf("Expires=") + "Expires=".length
        return header.substring(start, header.indexOf(';', start))
    }

    @ParameterizedTest
    @ValueSource(strings = ["provider", "forged-processing", "db-completion"])
    fun given__exception_containing_private_tokens__when__redirecting_failure__then__emit_only_fixed_url_and_type_only_log(
        source: String,
    ) {
        val response = response()
        val request = mock(HttpServletRequest::class.java)
        val logger = LoggerFactory.getLogger(
            if (source == "db-completion") SocialOAuth2SuccessHandler::class.java else SocialOAuth2FailureHandler::class.java,
        ) as Logger
        val events = ListAppender<ILoggingEvent>()
        events.start()
        logger.addAppender(events)
        try {
            if (source == "db-completion") {
                val login = mock(SocialLogin::class.java)
                val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")
                `when`(login.complete(user)).thenThrow(IllegalStateException(PRIVATE))
                SocialOAuth2SuccessHandler(login, FRONT).onAuthenticationSuccess(
                    request,
                    response,
                    UsernamePasswordAuthenticationToken(SocialOAuthPrincipal(user, mapOf()), null),
                )
            } else {
                val error = OAuth2AuthenticationException(
                    OAuth2Error(if (source == "provider") "server_error" else "oauth2_processing_error", PRIVATE, null),
                )
                SocialOAuth2FailureHandler(FRONT).onAuthenticationFailure(request, response, error)
            }

            val url = ArgumentCaptor.forClass(String::class.java)
            verify(response).sendRedirect(url.capture())
            assertFalse(url.value.contains(PRIVATE))
            assertTrue(url.value.startsWith("https://fixture.invalid/login?error="))
            assertFalse(events.list.isEmpty())
            for (event in events.list) {
                assertFalse(event.formattedMessage.contains(PRIVATE))
                assertNull(event.throwableProxy)
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["?tenant=a", "#oauth-error"])
    fun given__frontend_query_or_fragment__when__token_completion_fails__then__preserve_them_and_append_the_fixed_error_parameter(
        suffix: String,
    ) {
        val login = mock(SocialLogin::class.java)
        val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")
        `when`(login.complete(user)).thenThrow(IllegalStateException(PRIVATE))
        val response = response()

        SocialOAuth2SuccessHandler(login, FRONT + suffix).onAuthenticationSuccess(
            mock(HttpServletRequest::class.java),
            response,
            UsernamePasswordAuthenticationToken(SocialOAuthPrincipal(user, mapOf()), null),
        )

        val url = ArgumentCaptor.forClass(String::class.java)
        verify(response).sendRedirect(url.capture())
        val target = URI.create(url.value)
        val error = URLEncoder.encode("로그인 처리 중 오류가 발생했습니다", StandardCharsets.UTF_8)
        assertEquals("/login", target.path)
        assertEquals(if (suffix.startsWith("?")) "tenant=a&error=" + error else "error=" + error, target.rawQuery)
        assertEquals(if (suffix.startsWith("#")) "oauth-error" else null, target.fragment)
        assertFalse(url.value.contains(PRIVATE))
        verify(login).complete(user)
    }

    @Test
    fun given__known_static_local_account_conflict__when__oauth_fails__then__retain_only_that_allowed_reason() {
        val response = response()
        val message = "이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요."

        SocialOAuth2FailureHandler(FRONT).onAuthenticationFailure(
            mock(HttpServletRequest::class.java),
            response,
            OAuth2AuthenticationException(OAuth2Error("oauth2_processing_error", message, null)),
        )

        val url = ArgumentCaptor.forClass(String::class.java)
        verify(response).sendRedirect(url.capture())
        assertTrue(url.value.contains(URLEncoder.encode(message, StandardCharsets.UTF_8)))
    }

    companion object {
        private const val FRONT = "https://fixture.invalid/oauth/callback"
        private const val PRIVATE = "synthetic-private-access-refresh-secret"
        private const val SYNTHETIC_REFRESH = "c3ludGhldGljLXJlZnJlc2gtdG9rZW4tdmFsdWUtMDEy"
    }
}
