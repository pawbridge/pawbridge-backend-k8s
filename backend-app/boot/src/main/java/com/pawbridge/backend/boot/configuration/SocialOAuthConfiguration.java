package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuth2UserService;
import com.pawbridge.backend.user.internal.oauth.web.SocialOAuth2FailureHandler;
import com.pawbridge.backend.user.internal.oauth.web.SocialOAuth2SuccessHandler;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods=false)
@ConditionalOnExpression("${pawbridge.auth.google-enabled:false} or ${pawbridge.auth.naver-enabled:false}")
@Import(SocialOAuthBusinessConfiguration.class)
public class SocialOAuthConfiguration {
    private static final String NAVER_AUTHORIZATION_URI="https://nid.naver.com/oauth2.0/authorize";
    private static final String NAVER_TOKEN_URI="https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_USER_INFO_URI="https://openapi.naver.com/v1/nid/me";

    /** Registers only the enabled providers, so a disabled provider has no authorization or callback path. */
    @Bean InMemoryClientRegistrationRepository socialClientRegistrationRepository(
            @Value("${pawbridge.auth.google-enabled:false}") boolean googleEnabled,
            @Value("${spring.security.oauth2.client.registration.google.client-id:${GOOGLE_CLIENT_ID:}}") String googleClientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:${GOOGLE_SECRET_KEY:}}") String googleSecret,
            @Value("${spring.security.oauth2.client.registration.google.redirect-uri:${REDIRECT_URI:}}") String googleCallback,
            @Value("${pawbridge.auth.naver-enabled:false}") boolean naverEnabled,
            @Value("${spring.security.oauth2.client.registration.naver.client-id:${NAVER_CLIENT_ID:}}") String naverClientId,
            @Value("${spring.security.oauth2.client.registration.naver.client-secret:${NAVER_CLIENT_SECRET:}}") String naverSecret,
            @Value("${spring.security.oauth2.client.registration.naver.redirect-uri:${NAVER_REDIRECT_URI:}}") String naverCallback){
        List<ClientRegistration> registrations=new ArrayList<>();
        if(googleEnabled) {
            registrations.add(google(googleClientId,googleSecret,googleCallback));
        }
        if(naverEnabled) {
            registrations.add(naver(naverClientId,naverSecret,naverCallback));
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    private static ClientRegistration google(String clientId,String secret,String callback) {
        requireCredentials("Google",clientId,secret);
        requireUri(callback,"/login/oauth2/code/google");
        return CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(clientId).clientSecret(secret)
                .scope("email","profile").redirectUri(callback)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth?prompt=select_account").build();
    }

    /** Naver is not a Spring common provider; its user-info body nests the profile under "response". */
    private static ClientRegistration naver(String clientId,String secret,String callback) {
        requireCredentials("Naver",clientId,secret);
        requireUri(callback,"/login/oauth2/code/naver");
        return ClientRegistration.withRegistrationId("naver")
                .clientId(clientId)
                .clientSecret(secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callback)
                .authorizationUri(NAVER_AUTHORIZATION_URI)
                .tokenUri(NAVER_TOKEN_URI)
                .userInfoUri(NAVER_USER_INFO_URI)
                .userNameAttributeName("response")
                .clientName("Naver")
                .build();
    }

    private static void requireCredentials(String provider,String clientId,String secret) {
        if(clientId.isBlank()||secret.isBlank()) {
            throw new IllegalArgumentException("Explicit "+provider+" client credentials required");
        }
    }
    @Bean SocialOAuth2UserService googleOAuth2UserService(SocialLogin login){return new SocialOAuth2UserService(login);}
    @Bean SocialOAuth2SuccessHandler googleOAuth2SuccessHandler(SocialLogin login,@Value("${oauth2.redirect-uri:${OAUTH2_REDIRECT_URI:}}") String frontend){requireUri(frontend,null);return new SocialOAuth2SuccessHandler(login,frontend);}
    @Bean SocialOAuth2FailureHandler googleOAuth2FailureHandler(@Value("${oauth2.redirect-uri:${OAUTH2_REDIRECT_URI:}}") String frontend){requireUri(frontend,null);return new SocialOAuth2FailureHandler(frontend);}
    @Bean
    @Order(1)
    SecurityFilterChain googleOAuthSecurity(
            HttpSecurity http,
            InMemoryClientRegistrationRepository clients,
            SocialOAuth2UserService users,
            SocialOAuth2SuccessHandler success,
            SocialOAuth2FailureHandler failure,
            @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) String corsOriginPatterns) throws Exception {
        var securityContextRepository = new RequestAttributeSecurityContextRepository();
        var authorizationRequestRepository = new HttpSessionOAuth2AuthorizationRequestRepository();

        return http
                .securityMatcher(oauthPaths(clients))
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
    private static String[] oauthPaths(InMemoryClientRegistrationRepository clients) {
        List<String> paths=new ArrayList<>();
        for(ClientRegistration registration:clients) {
            paths.add("/oauth2/authorization/"+registration.getRegistrationId());
            paths.add("/login/oauth2/code/"+registration.getRegistrationId());
        }
        return paths.toArray(String[]::new);
    }
    private static void requireUri(String value,String path){
        try{var uri=URI.create(value);if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null
                ||uri.getRawUserInfo()!=null||uri.getFragment()!=null||(path!=null&&!path.equals(uri.getPath())))throw new IllegalArgumentException();}
        catch(RuntimeException failure){throw new IllegalArgumentException("Explicit OAuth callback and frontend addresses required");}
    }
}
