package com.pawbridge.userservice.oauth2.handler;

import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.RefreshTokenRepository;
import com.pawbridge.userservice.security.PrincipalDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OAuth2SuccessHandlerTest {

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Authentication authentication;

    @Mock
    private RedirectStrategy redirectStrategy;

    private OAuth2SuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2SuccessHandler(jwtProvider, refreshTokenRepository);
        handler.setRedirectStrategy(redirectStrategy);
        ReflectionTestUtils.setField(handler, "redirectUri", "https://www.pawbridge.kr/oauth/callback");
    }

    @Test
    void givenOAuth2Login_whenAuthenticationSucceeds_thenTokensAreRedirectedButNotLogged(CapturedOutput output)
            throws Exception {
        String accessToken = "access-token-must-not-appear-in-log";
        String refreshToken = "refresh-token-must-not-appear-in-log";
        User user = User.builder()
                .userId(7L)
                .email("user@example.com")
                .name("사용자")
                .nickname("user7")
                .role(Role.ROLE_USER)
                .provider("GOOGLE")
                .providerId("google-7")
                .build();
        PrincipalDetails principal = new PrincipalDetails(user, Map.of("sub", "google-7"));

        when(authentication.getPrincipal()).thenReturn(principal);
        when(jwtProvider.createAccessToken(user)).thenReturn(accessToken);
        when(jwtProvider.createRefreshToken()).thenReturn(refreshToken);
        when(jwtProvider.getRefreshTokenExpiration()).thenReturn(1_209_600_000L);
        when(refreshTokenRepository.findByUserId(7L)).thenReturn(Optional.empty());

        handler.onAuthenticationSuccess(request, response, authentication);

        ArgumentCaptor<String> redirectUrl = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(eq(request), eq(response), redirectUrl.capture());
        assertThat(redirectUrl.getValue())
                .contains("accessToken=" + accessToken)
                .contains("refreshToken=" + refreshToken);
        assertThat(output)
                .doesNotContain(accessToken)
                .doesNotContain(refreshToken);
    }
}
