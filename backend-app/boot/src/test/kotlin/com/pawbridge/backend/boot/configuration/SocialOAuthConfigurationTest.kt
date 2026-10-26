package com.pawbridge.backend.boot.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

class SocialOAuthConfigurationTest {
    private val configuration = SocialOAuthConfiguration()

    @Test
    fun `네이버만 켜졌을 때__등록하면__네이버 엔드포인트만 노출하고 구글 클라이언트는 없다`() {
        // given
        // when
        val clients = configuration.socialClientRegistrationRepository(
            false, "", "", "", true, "naver-client", "naver-secret", NAVER_CALLBACK,
        )

        // then
        assertThat(clients.findByRegistrationId("google")).isNull()
        val naver: ClientRegistration = clients.findByRegistrationId("naver")
        assertThat(naver.clientId).isEqualTo("naver-client")
        assertThat(naver.redirectUri).isEqualTo(NAVER_CALLBACK)
        assertThat(naver.clientAuthenticationMethod).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST)
        assertThat(naver.providerDetails.authorizationUri).isEqualTo("https://nid.naver.com/oauth2.0/authorize")
        assertThat(naver.providerDetails.tokenUri).isEqualTo("https://nid.naver.com/oauth2.0/token")
        assertThat(naver.providerDetails.userInfoEndpoint.uri).isEqualTo("https://openapi.naver.com/v1/nid/me")
        assertThat(naver.providerDetails.userInfoEndpoint.userNameAttributeName).isEqualTo("response")
    }

    @Test
    fun `둘 다 켜졌을 때__등록하면__구글은 그대로 두고 네이버를 함께 등록한다`() {
        // given
        // when
        val clients = configuration.socialClientRegistrationRepository(
            true, "google-client", "google-secret", "https://fixture.invalid/login/oauth2/code/google",
            true, "naver-client", "naver-secret", NAVER_CALLBACK,
        )

        // then
        assertThat(clients.findByRegistrationId("google").scopes).containsExactlyInAnyOrder("email", "profile")
        assertThat(clients.findByRegistrationId("naver")).isNotNull()
    }

    @Test
    fun `네이버가 인증 정보 없이 켜졌을 때__등록하면__즉시 실패한다`() {
        // given
        // when
        val registering = {
            configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "", "naver-secret", NAVER_CALLBACK,
            )
        }

        // then
        assertThatThrownBy { registering() }.hasMessage("Explicit Naver client credentials required")
    }

    @Test
    fun `네이버 콜백이 다른 제공자 경로일 때__등록하면__즉시 실패한다`() {
        // given
        // when
        val registering = {
            configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "naver-client", "naver-secret",
                "https://fixture.invalid/login/oauth2/code/google",
            )
        }

        // then
        assertThatThrownBy { registering() }.hasMessage("Explicit OAuth callback and frontend addresses required")
    }

    companion object {
        private const val NAVER_CALLBACK = "https://fixture.invalid/login/oauth2/code/naver"
    }
}
