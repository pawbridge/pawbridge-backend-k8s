package com.pawbridge.backend.user.internal.oauth.web;

import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.GoogleOAuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

public final class GoogleOAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private static final Logger LOG=LoggerFactory.getLogger(GoogleOAuth2SuccessHandler.class);
    private final GoogleLogin login;
    private final String redirectUri;
    public GoogleOAuth2SuccessHandler(GoogleLogin login,String redirectUri){this.login=login;this.redirectUri=redirectUri;}
    @Override public void onAuthenticationSuccess(HttpServletRequest request,HttpServletResponse response,Authentication authentication)throws IOException{
        try {
            var principal=(GoogleOAuthPrincipal)authentication.getPrincipal();
            var tokens=login.complete(principal.user());
            String target=UriComponentsBuilder.fromUriString(redirectUri).queryParam("accessToken",tokens.accessToken())
                    .queryParam("refreshToken",tokens.refreshToken()).build().toUriString();
            response.sendRedirect(response.encodeRedirectURL(target));
        } catch(Exception failure){
            LOG.error("OAuth2 token completion failed; exceptionType={}",failure.getClass().getName());
            String message=URLEncoder.encode("로그인 처리 중 오류가 발생했습니다",StandardCharsets.UTF_8);
            String target=UriComponentsBuilder.fromUriString(redirectUri.replace("/oauth/callback","/login"))
                    .queryParam("error",message).build().toUriString();
            response.sendRedirect(response.encodeRedirectURL(target));
        }
    }
}
