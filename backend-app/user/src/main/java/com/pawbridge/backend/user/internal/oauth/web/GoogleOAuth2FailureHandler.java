package com.pawbridge.backend.user.internal.oauth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.web.util.UriComponentsBuilder;

public final class GoogleOAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {
    private static final Logger LOG=LoggerFactory.getLogger(GoogleOAuth2FailureHandler.class);
    private static final Set<String> SAFE=Set.of("이메일 정보를 가져올 수 없습니다","이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.","로그인 처리 중 오류가 발생했습니다");
    private final String redirectUri;
    public GoogleOAuth2FailureHandler(String redirectUri){this.redirectUri=redirectUri;}
    @Override public void onAuthenticationFailure(HttpServletRequest request,HttpServletResponse response,AuthenticationException exception)throws IOException{
        String message="로그인에 실패했습니다";
        if(exception instanceof OAuth2AuthenticationException oauth && "oauth2_processing_error".equals(oauth.getError().getErrorCode())
                && oauth.getError().getDescription()!=null && SAFE.contains(oauth.getError().getDescription()))message=oauth.getError().getDescription();
        LOG.warn("OAuth2 authentication failed; exceptionType={}",exception.getClass().getName());
        String encoded=URLEncoder.encode(message,StandardCharsets.UTF_8);
        response.sendRedirect(response.encodeRedirectURL(UriComponentsBuilder.fromUriString(redirectUri).replacePath("/login")
                .queryParam("error",encoded).build().toUriString()));
    }
}
