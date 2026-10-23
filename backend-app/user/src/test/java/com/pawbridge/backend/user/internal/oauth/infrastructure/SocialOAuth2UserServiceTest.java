package com.pawbridge.backend.user.internal.oauth.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.*;

class SocialOAuth2UserServiceTest {
    @SuppressWarnings("unchecked") private final OAuth2UserService<OAuth2UserRequest,OAuth2User> remote=mock(OAuth2UserService.class);
    private final SocialLogin login=mock(SocialLogin.class);
    private OAuth2UserRequest request(){
        var client=ClientRegistration.withRegistrationId("google").clientId("synthetic").clientSecret("synthetic")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://fixture.invalid/login/oauth2/code/google")
                .authorizationUri("https://fixture.invalid/authorize").tokenUri("https://fixture.invalid/token")
                .userInfoUri("https://fixture.invalid/profile").userNameAttributeName("sub").build();
        return new OAuth2UserRequest(client,new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"synthetic-remote",Instant.now(),Instant.now().plusSeconds(60)));
    }
    @Test void given__google_profile__when__loading__then__delegate_protocol_and_return_only_the_persisted_identity(){
        var request=request();Map<String,Object> attributes=Map.of("sub","remote-id","email","fixture@example.invalid","name","name");
        when(remote.loadUser(request)).thenReturn(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),attributes,"sub"));
        var user=new SocialLogin.SocialUser(7,"fixture@example.invalid","name","ROLE_USER",null,"GOOGLE");when(login.load("google",attributes)).thenReturn(user);
        var principal=(SocialOAuthPrincipal)new SocialOAuth2UserService(login,remote).loadUser(request);
        assertSame(user,principal.user());assertEquals(attributes,principal.getAttributes());assertEquals(user.email(),principal.getName());
        assertEquals("ROLE_USER",principal.getAuthorities().iterator().next().getAuthority());assertEquals("SocialOAuthPrincipal",principal.toString());
        var order=inOrder(remote,login);order.verify(remote).loadUser(request);order.verify(login).load("google",attributes);
    }
    @ParameterizedTest @ValueSource(strings={"runtime","remote-oauth","forged-business"})
    void given__unexpected_profile_processing_failure__when__loading__then__wrap_with_fixed_error_without_private_cause(String source){
        var request=request();
        if(source.equals("forged-business")){
            Map<String,Object> attributes=Map.of("sub","remote-id");
            when(remote.loadUser(request)).thenReturn(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),attributes,"sub"));
            when(login.load("google",attributes)).thenThrow(new SocialLoginRejectedException("synthetic-private-access-token"));
        }else when(remote.loadUser(request)).thenThrow(source.equals("remote-oauth")?
                new OAuth2AuthenticationException(new OAuth2Error("provider-error","synthetic-private-access-token",null)):
                new IllegalStateException("synthetic-private-access-token"));
        var error=assertThrows(OAuth2AuthenticationException.class,()->new SocialOAuth2UserService(login,remote).loadUser(request));
        assertEquals("로그인 처리 중 오류가 발생했습니다",error.getError().getDescription());assertFalse(error.getMessage().contains("synthetic-private-access-token"));assertNull(error.getCause());if(!source.equals("forged-business"))verifyNoInteractions(login);
    }
}
