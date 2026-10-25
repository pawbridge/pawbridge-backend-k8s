package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import java.time.Instant

class SocialOAuth2UserServiceTest {
    private val remote = mockk<OAuth2UserService<OAuth2UserRequest, OAuth2User>>()
    private val login = mockk<SocialLogin>()

    private fun request(): OAuth2UserRequest {
        val client = ClientRegistration.withRegistrationId("google")
            .clientId("synthetic")
            .clientSecret("synthetic")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://fixture.invalid/login/oauth2/code/google")
            .authorizationUri("https://fixture.invalid/authorize")
            .tokenUri("https://fixture.invalid/token")
            .userInfoUri("https://fixture.invalid/profile")
            .userNameAttributeName("sub")
            .build()
        return OAuth2UserRequest(
            client,
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "synthetic-remote",
                Instant.now(),
                Instant.now().plusSeconds(60),
            ),
        )
    }

    @Test
    fun `구글 프로필일 때__불러오면__프로토콜을 위임하고 저장된 신원만 돌려준다`() {
        // given
        val request = request()
        val attributes: MutableMap<String, Any?> = mutableMapOf(
            "sub" to "remote-id",
            "email" to "fixture@example.invalid",
            "name" to "name",
        )
        val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")

        every { remote.loadUser(any()) } returns
            DefaultOAuth2User(listOf(SimpleGrantedAuthority("OAUTH2_USER")), attributes, "sub")
        every { login.load(any(), any()) } returns user

        // when
        val principal = SocialOAuth2UserService(login, remote).loadUser(request) as SocialOAuthPrincipal

        // then
        assertSame(user, principal.user())
        assertEquals(attributes, principal.getAttributes())
        assertEquals(user.email, principal.getName())
        assertEquals("ROLE_USER", principal.getAuthorities().iterator().next().authority)
        assertEquals("SocialOAuthPrincipal", principal.toString())
        verifySequence {
            remote.loadUser(request)
            login.load("google", attributes)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["runtime", "remote-oauth", "forged-business"])
    fun `예기치 않은 프로필 처리 실패일 때__불러오면__비공개 원인 없이 고정된 오류로 감싼다`(
        source: String,
    ) {
        // given
        val request = request()

        if (source == "forged-business") {
            val attributes: MutableMap<String, Any?> = mutableMapOf("sub" to "remote-id")
            every { remote.loadUser(any()) } returns
                DefaultOAuth2User(listOf(SimpleGrantedAuthority("OAUTH2_USER")), attributes, "sub")
            every { login.load(any(), any()) } throws SocialLoginRejectedException("synthetic-private-access-token")
        } else {
            every { remote.loadUser(any()) } throws
                if (source == "remote-oauth") {
                    OAuth2AuthenticationException(OAuth2Error("provider-error", "synthetic-private-access-token", null))
                } else {
                    IllegalStateException("synthetic-private-access-token")
                }
        }

        // when
        val error = assertThrows(OAuth2AuthenticationException::class.java) {
            SocialOAuth2UserService(login, remote).loadUser(request)
        }

        // then
        assertEquals("로그인 처리 중 오류가 발생했습니다", error.error.description)
        assertFalse(error.message!!.contains("synthetic-private-access-token"))
        assertNull(error.cause)
        if (source != "forged-business") {
            verify(exactly = 0) { login.load(any(), any()) }
            verify(exactly = 0) { login.complete(any()) }
        }
    }
}
