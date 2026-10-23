package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class SocialOAuthConfigurationTest {
    private static final String NAVER_CALLBACK = "https://fixture.invalid/login/oauth2/code/naver";
    private final SocialOAuthConfiguration configuration = new SocialOAuthConfiguration();

    @Test
    void given__only_naver_enabled__when__registering__then__expose_naver_endpoints_and_no_google_client() {
        var clients = configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "naver-client", "naver-secret", NAVER_CALLBACK);

        assertThat(clients.findByRegistrationId("google")).isNull();
        ClientRegistration naver = clients.findByRegistrationId("naver");
        assertThat(naver.getClientId()).isEqualTo("naver-client");
        assertThat(naver.getRedirectUri()).isEqualTo(NAVER_CALLBACK);
        assertThat(naver.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(naver.getProviderDetails().getAuthorizationUri()).isEqualTo("https://nid.naver.com/oauth2.0/authorize");
        assertThat(naver.getProviderDetails().getTokenUri()).isEqualTo("https://nid.naver.com/oauth2.0/token");
        assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://openapi.naver.com/v1/nid/me");
        assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("response");
    }

    @Test
    void given__both_enabled__when__registering__then__keep_google_unchanged_beside_naver() {
        var clients = configuration.socialClientRegistrationRepository(
                true, "google-client", "google-secret", "https://fixture.invalid/login/oauth2/code/google",
                true, "naver-client", "naver-secret", NAVER_CALLBACK);

        assertThat(clients.findByRegistrationId("google").getScopes()).containsExactlyInAnyOrder("email", "profile");
        assertThat(clients.findByRegistrationId("naver")).isNotNull();
    }

    @Test
    void given__naver_enabled_without_credentials__when__registering__then__fail_fast() {
        assertThatThrownBy(() -> configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "", "naver-secret", NAVER_CALLBACK))
                .hasMessage("Explicit Naver client credentials required");
    }

    @Test
    void given__naver_callback_on_another_provider_path__when__registering__then__fail_fast() {
        assertThatThrownBy(() -> configuration.socialClientRegistrationRepository(
                false, "", "", "", true, "naver-client", "naver-secret",
                "https://fixture.invalid/login/oauth2/code/google"))
                .hasMessage("Explicit OAuth callback and frontend addresses required");
    }
}
