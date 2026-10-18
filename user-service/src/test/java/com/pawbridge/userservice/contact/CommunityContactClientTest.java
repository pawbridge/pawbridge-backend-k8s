package com.pawbridge.userservice.contact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.mock.env.MockEnvironment;

class CommunityContactClientTest {
    private final String configuredUrl = CommunityContactClient.class.getAnnotation(FeignClient.class).url();

    @Test
    void givenNoOverride_whenUrlResolved_thenUseCommunityServicePort() {
        assertThat(new MockEnvironment().resolveRequiredPlaceholders(configuredUrl))
                .isEqualTo("http://community-service:8082");
    }

    @Test
    void givenEnvironmentOverride_whenUrlResolved_thenUseConfiguredAddress() {
        assertThat(new MockEnvironment().withProperty("COMMUNITY_SERVICE_URL", "http://dev-community:28082")
                .resolveRequiredPlaceholders(configuredUrl)).isEqualTo("http://dev-community:28082");
    }

    @Test
    void givenBothOverrides_whenUrlResolved_thenServicePropertyWins() {
        assertThat(new MockEnvironment().withProperty("COMMUNITY_SERVICE_URL", "http://dev-community:28082")
                .withProperty("service.community.url", "http://community:8082")
                .resolveRequiredPlaceholders(configuredUrl)).isEqualTo("http://community:8082");
    }
}
