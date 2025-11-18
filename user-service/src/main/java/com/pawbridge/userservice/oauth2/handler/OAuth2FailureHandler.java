package com.pawbridge.userservice.oauth2.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * OAuth2 로그인 실패 시 처리 핸들러
 * 에러 메시지와 함께 로그인 페이지로 리다이렉트
 */
@Component
@Slf4j
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Value("${oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        String errorMessage = exception.getMessage() != null ?
                exception.getMessage() : "로그인에 실패했습니다";

        log.error("OAuth2 로그인 실패: {}", errorMessage);

        // 에러 메시지 인코딩
        String encodedError = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);

        // 로그인 페이지로 리다이렉트
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .replacePath("/login")
                .queryParam("error", encodedError)
                .build()
                .toUriString();

        log.info("OAuth2 실패 리다이렉트: {}", targetUrl);

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
