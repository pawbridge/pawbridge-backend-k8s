package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.GoogleOAuth2UserService;
import com.pawbridge.backend.user.internal.oauth.web.GoogleOAuth2FailureHandler;
import com.pawbridge.backend.user.internal.oauth.web.GoogleOAuth2SuccessHandler;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.auth",name="google-enabled",havingValue="true",matchIfMissing=false)
@Import(GoogleOAuthBusinessConfiguration.class)
public class GoogleOAuthConfiguration {
    @Bean ClientRegistrationRepository googleClientRegistrationRepository(
            @Value("${spring.security.oauth2.client.registration.google.client-id:${GOOGLE_CLIENT_ID:}}") String clientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:${GOOGLE_SECRET_KEY:}}") String secret,
            @Value("${spring.security.oauth2.client.registration.google.redirect-uri:${REDIRECT_URI:}}") String callback){
        if(clientId.isBlank()||secret.isBlank())throw new IllegalArgumentException("Explicit Google client credentials required");
        requireUri(callback,"/login/oauth2/code/google");
        var registration=CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(clientId).clientSecret(secret)
                .scope("email","profile").redirectUri(callback)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth?prompt=select_account").build();
        return new InMemoryClientRegistrationRepository(registration);
    }
    @Bean GoogleOAuth2UserService googleOAuth2UserService(GoogleLogin login){return new GoogleOAuth2UserService(login);}
    @Bean GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler(GoogleLogin login,@Value("${oauth2.redirect-uri:${OAUTH2_REDIRECT_URI:}}") String frontend){requireUri(frontend,null);return new GoogleOAuth2SuccessHandler(login,frontend);}
    @Bean GoogleOAuth2FailureHandler googleOAuth2FailureHandler(@Value("${oauth2.redirect-uri:${OAUTH2_REDIRECT_URI:}}") String frontend){requireUri(frontend,null);return new GoogleOAuth2FailureHandler(frontend);}
    @Bean @Order(1)
    SecurityFilterChain googleOAuthSecurity(HttpSecurity http,ClientRegistrationRepository clients,GoogleOAuth2UserService users,
            GoogleOAuth2SuccessHandler success,GoogleOAuth2FailureHandler failure)throws Exception{
        return http.securityMatcher("/oauth2/authorization/google","/login/oauth2/code/google")
                .csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context->context.securityContextRepository(new RequestAttributeSecurityContextRepository()).requireExplicitSave(true))
                .authorizeHttpRequests(requests->requests.anyRequest().permitAll())
                .oauth2Login(oauth->oauth.clientRegistrationRepository(clients)
                    .authorizationEndpoint(endpoint->endpoint.authorizationRequestRepository(new HttpSessionOAuth2AuthorizationRequestRepository()))
                    .userInfoEndpoint(endpoint->endpoint.userService(users)).successHandler(success).failureHandler(failure))
                .build();
    }
    private static void requireUri(String value,String path){
        try{var uri=URI.create(value);if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null
                ||uri.getRawUserInfo()!=null||uri.getFragment()!=null||(path!=null&&!path.equals(uri.getPath())))throw new IllegalArgumentException();}
        catch(RuntimeException failure){throw new IllegalArgumentException("Explicit Google callback and frontend addresses required");}
    }
}
