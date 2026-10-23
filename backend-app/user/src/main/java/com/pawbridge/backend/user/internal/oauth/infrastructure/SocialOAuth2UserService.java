package com.pawbridge.backend.user.internal.oauth.infrastructure;

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class SocialOAuth2UserService implements OAuth2UserService<OAuth2UserRequest,OAuth2User> {
    private static final java.util.Set<String> SAFE_REASONS=java.util.Set.of("이메일 정보를 가져올 수 없습니다","이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.","이미 다른 소셜 계정에 연결된 이메일입니다. 관리자에게 문의해주세요.","로그인 처리 중 오류가 발생했습니다");
    private static final Logger LOG=LoggerFactory.getLogger(SocialOAuth2UserService.class);
    private final SocialLogin login;
    private final OAuth2UserService<OAuth2UserRequest,OAuth2User> remote;
    public SocialOAuth2UserService(SocialLogin login){this(login,new DefaultOAuth2UserService());}
    public SocialOAuth2UserService(SocialLogin login,OAuth2UserService<OAuth2UserRequest,OAuth2User> remote){this.login=login;this.remote=remote;}
    @Override public OAuth2User loadUser(OAuth2UserRequest request){
        try {
            OAuth2User raw=remote.loadUser(request);
            return new SocialOAuthPrincipal(login.load(request.getClientRegistration().getRegistrationId(),raw.getAttributes()),raw.getAttributes());
        } catch(SocialLoginRejectedException rejected){
            String reason=SAFE_REASONS.contains(rejected.getMessage())?rejected.getMessage():"로그인 처리 중 오류가 발생했습니다";
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth2_processing_error",reason,null));
        } catch(RuntimeException failure){
            LOG.error("OAuth2 user processing failed; exceptionType={}",failure.getClass().getName());
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth2_processing_error","로그인 처리 중 오류가 발생했습니다",null));
        }
    }
}
