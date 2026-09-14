package com.pawbridge.userservice.oauth2.handler;

import com.pawbridge.userservice.support.TestLogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2FailureLogPrivacyTest {
    @Test
    void givenFailureWithPrivateMessage_whenRedirected_thenExistingResponseButNoMessageOrUrlInLog() throws Exception {
        var handler = new OAuth2FailureHandler();
        ReflectionTestUtils.setField(handler, "redirectUri", "https://client.example.test/oauth/callback");
        String message = "private-oauth@example.test";
        var response = new MockHttpServletResponse();
        try (var logs = new TestLogCapture(OAuth2FailureHandler.class)) {
            handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                    new BadCredentialsException(message));
            assertThat(response.getRedirectedUrl()).startsWith("https://client.example.test/login?")
                    .contains(URLEncoder.encode(message, StandardCharsets.UTF_8));
            assertThat(logs.text()).contains("BadCredentialsException")
                    .doesNotContain(message, response.getRedirectedUrl(),
                            URLEncoder.encode(message, StandardCharsets.UTF_8));
        }
    }
}
