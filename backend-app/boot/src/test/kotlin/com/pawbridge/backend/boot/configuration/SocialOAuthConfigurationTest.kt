package com.pawbridge.backend.boot.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

class SocialOAuthConfigurationTest {
    private val configuration = SocialOAuthConfiguration()

    @Test
    fun given__only_naver_enabled__when__registering__then__expose_naver_endpoints_and_no_google_client() {
        val clients = configuration.socialClientRegistrationRepository(
            false, "", "", "", true, "naver-client", "naver-secret", NAVER_CALLBACK,
        )

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
    fun given__both_enabled__when__registering__then__keep_google_unchanged_beside_naver() {
        val clients = configuration.socialClientRegistrationRepository(
            true, "google-client", "google-secret", "https://fixture.invalid/login/oauth2/code/google",
            true, "naver-client", "naver-secret", NAVER_CALLBACK,
        )

        assertThat(clients.findByRegistrationId("google").scopes).containsExactlyInAnyOrder("email", "profile")
        assertThat(clients.findByRegistrationId("naver")).isNotNull()
    }

    @Test
    fun given__naver_enabled_without_credentials__when__registering__then__fail_fast() {
        assertThatThrownBy {
            configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "", "naver-secret", NAVER_CALLBACK,
            )
        }.hasMessage("Explicit Naver client credentials required")
    }

    @Test
    fun given__naver_callback_on_another_provider_path__when__registering__then__fail_fast() {
        assertThatThrownBy {
            configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "naver-client", "naver-secret",
                "https://fixture.invalid/login/oauth2/code/google",
            )
        }.hasMessage("Explicit OAuth callback and frontend addresses required")
    }

    companion object {
        private const val NAVER_CALLBACK = "https://fixture.invalid/login/oauth2/code/naver"
    }
}
