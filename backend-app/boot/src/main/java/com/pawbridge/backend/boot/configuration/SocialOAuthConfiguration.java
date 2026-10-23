package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuth2UserService;
import com.pawbridge.backend.user.internal.oauth.web.SocialOAuth2FailureHandler;
import com.pawbridge.backend.user.internal.oauth.web.SocialOAuth2SuccessHandler;
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
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.auth",name="google-enabled",havingValue="true",matchIfMissing=false)
@Import(SocialOAuthBusinessConfiguration.class)
public class SocialOAuthConfiguration {
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
    @Bean SocialOAuth2UserService googleOAuth2UserService(SocialLogin login){return new SocialOAuth2UserService(login);}
    @Bean SocialOAuth2SuccessHandler googleOAuth2SuccessHandler(SocialLogin login,@Value("${oauth2.redirect-uri:${OAUTH2_REDIRECT_URI:}}") String frontend){requireUri(frontend,null);return new SocialOAuth2SuccessHandler(login,frontend);}
    @Bean SocialOAuth2FailureHandler googleOAuth2FailureHandler(@Value("${oauth2.redirect-uri:${OAUTH2_REDIRECT_URI:}}") String frontend){requireUri(frontend,null);return new SocialOAuth2FailureHandler(frontend);}
    @Bean
    @Order(1)
    SecurityFilterChain googleOAuthSecurity(
            HttpSecurity http,
            ClientRegistrationRepository clients,
            SocialOAuth2UserService users,
            SocialOAuth2SuccessHandler success,
            SocialOAuth2FailureHandler failure,
            @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) String corsOriginPatterns) throws Exception {
        var securityContextRepository = new RequestAttributeSecurityContextRepository();
        var authorizationRequestRepository = new HttpSessionOAuth2AuthorizationRequestRepository();

        return http
                .securityMatcher("/oauth2/authorization/google", "/login/oauth2/code/google")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // Same CORS policy as every other chain, so the policy cannot differ by which chain matches a path.
                .addFilterAt(GatewayCors.filter(corsOriginPatterns), CorsFilter.class)
                .oauth2Login(oauth -> oauth
                        .clientRegistrationRepository(clients)
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestRepository(authorizationRequestRepository))
                        .userInfoEndpoint(endpoint -> endpoint.userService(users))
                        .successHandler(success)
                        .failureHandler(failure))
                .build();
    }
    private static void requireUri(String value,String path){
        try{var uri=URI.create(value);if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null
                ||uri.getRawUserInfo()!=null||uri.getFragment()!=null||(path!=null&&!path.equals(uri.getPath())))throw new IllegalArgumentException();}
        catch(RuntimeException failure){throw new IllegalArgumentException("Explicit Google callback and frontend addresses required");}
    }
}
