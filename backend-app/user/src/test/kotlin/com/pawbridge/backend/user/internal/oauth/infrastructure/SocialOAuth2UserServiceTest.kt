package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
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
    @Suppress("UNCHECKED_CAST")
    private val remote: OAuth2UserService<OAuth2UserRequest, OAuth2User> =
        mock(OAuth2UserService::class.java) as OAuth2UserService<OAuth2UserRequest, OAuth2User>
    private val login: SocialLogin = mock(SocialLogin::class.java)

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
    fun given__google_profile__when__loading__then__delegate_protocol_and_return_only_the_persisted_identity() {
        val request = request()
        val attributes: MutableMap<String, Any?> = mutableMapOf(
            "sub" to "remote-id",
            "email" to "fixture@example.invalid",
            "name" to "name",
        )
        `when`(remote.loadUser(request))
            .thenReturn(DefaultOAuth2User(listOf(SimpleGrantedAuthority("OAUTH2_USER")), attributes, "sub"))
        val user = SocialLogin.SocialUser(7, "fixture@example.invalid", "name", "ROLE_USER", null, "GOOGLE")
        `when`(login.load("google", attributes)).thenReturn(user)

        val principal = SocialOAuth2UserService(login, remote).loadUser(request) as SocialOAuthPrincipal

        assertSame(user, principal.user())
        assertEquals(attributes, principal.getAttributes())
        assertEquals(user.email, principal.getName())
        assertEquals("ROLE_USER", principal.getAuthorities().iterator().next().authority)
        assertEquals("SocialOAuthPrincipal", principal.toString())
        val order = inOrder(remote, login)
        order.verify(remote).loadUser(request)
        order.verify(login).load("google", attributes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["runtime", "remote-oauth", "forged-business"])
    fun given__unexpected_profile_processing_failure__when__loading__then__wrap_with_fixed_error_without_private_cause(
        source: String,
    ) {
        val request = request()
        if (source == "forged-business") {
            val attributes: MutableMap<String, Any?> = mutableMapOf("sub" to "remote-id")
            `when`(remote.loadUser(request))
                .thenReturn(DefaultOAuth2User(listOf(SimpleGrantedAuthority("OAUTH2_USER")), attributes, "sub"))
            `when`(login.load("google", attributes)).thenThrow(SocialLoginRejectedException("synthetic-private-access-token"))
        } else {
            `when`(remote.loadUser(request)).thenThrow(
                if (source == "remote-oauth") {
                    OAuth2AuthenticationException(OAuth2Error("provider-error", "synthetic-private-access-token", null))
                } else {
                    IllegalStateException("synthetic-private-access-token")
                },
            )
        }

        val error = assertThrows(OAuth2AuthenticationException::class.java) {
            SocialOAuth2UserService(login, remote).loadUser(request)
        }

        assertEquals("로그인 처리 중 오류가 발생했습니다", error.error.description)
        assertFalse(error.message!!.contains("synthetic-private-access-token"))
        assertNull(error.cause)
        if (source != "forged-business") {
            verifyNoInteractions(login)
        }
    }
}
