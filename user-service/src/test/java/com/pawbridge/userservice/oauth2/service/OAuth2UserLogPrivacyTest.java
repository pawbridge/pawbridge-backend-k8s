package com.pawbridge.userservice.oauth2.service;

import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.oauth2.exception.OAuth2ProcessingException;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.security.PrincipalDetails;
import com.pawbridge.userservice.service.NicknameGeneratorService;
import com.pawbridge.userservice.support.TestLogCapture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuth2UserLogPrivacyTest {
    @ParameterizedTest
    @ValueSource(strings = {"existing", "new", "collision", "unexpected"})
    void givenGoogleUser_whenLoaded_thenResultOrFailureIsPreservedWithoutLoggingPersonalData(String scenario) {
        String email = "private-oauth@example.test";
        String nickname = "PrivateNick";
        var users = mock(UserRepository.class);
        var nicknames = mock(NicknameGeneratorService.class);
        var service = new CustomOAuth2UserService(users, nicknames);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        service.setRestOperations(rest);
        server.expect(requestTo("https://identity.example.test/userinfo"))
                .andRespond(withSuccess("{\"sub\":\"private-provider-id\",\"email\":\"" + email
                        + "\",\"name\":\"PrivateName\"}", MediaType.APPLICATION_JSON));
        var registration = ClientRegistration.withRegistrationId("google").clientId("synthetic-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example.test/callback")
                .authorizationUri("https://identity.example.test/authorize")
                .tokenUri("https://identity.example.test/token")
                .userInfoUri("https://identity.example.test/userinfo")
                .userNameAttributeName("sub").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "synthetic-private-oauth-token",
                Instant.now(), Instant.now().plusSeconds(60));
        var user = User.builder().userId(7L).email(email).name("PrivateName")
                .nickname(nickname).provider("GOOGLE").role(Role.ROLE_USER).build();

        switch (scenario) {
            case "existing" -> when(users.findByEmailAndProvider(email, "GOOGLE")).thenReturn(Optional.of(user));
            case "new" -> {
                when(nicknames.generateUniqueNickname()).thenReturn(nickname);
                when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
            }
            case "collision" -> when(users.findByEmailAndProvider(email, "LOCAL")).thenReturn(Optional.of(user));
            case "unexpected" -> when(users.findByEmailAndProvider(email, "GOOGLE"))
                    .thenThrow(new IllegalStateException(email + " " + nickname));
        }

        try (var logs = new TestLogCapture(CustomOAuth2UserService.class)) {
            var request = new OAuth2UserRequest(registration, token);
            if (scenario.equals("collision") || scenario.equals("unexpected")) {
                assertThatThrownBy(() -> service.loadUser(request)).isInstanceOf(OAuth2ProcessingException.class);
                verify(users, never()).save(any());
            } else {
                var result = (PrincipalDetails) service.loadUser(request);
                assertThat(result.getUser().getEmail()).isEqualTo(email);
                assertThat(result.getUser().getNickname()).isEqualTo(nickname);
                if (scenario.equals("new")) verify(users).save(any(User.class));
            }
            assertThat(logs.text()).isNotBlank()
                    .doesNotContain(email, nickname, "PrivateName", token.getTokenValue());
        }
        server.verify();
    }
}
