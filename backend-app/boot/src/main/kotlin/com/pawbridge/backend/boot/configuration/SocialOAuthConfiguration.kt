package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialOAuth2UserService
import com.pawbridge.backend.user.internal.oauth.web.SocialOAuth2FailureHandler
import com.pawbridge.backend.user.internal.oauth.web.SocialOAuth2SuccessHandler
import java.net.URI
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.web.filter.CorsFilter

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("\${pawbridge.auth.google-enabled:false} or \${pawbridge.auth.naver-enabled:false}")
@Import(SocialOAuthBusinessConfiguration::class)
class SocialOAuthConfiguration {
    /** Registers only the enabled providers, so a disabled provider has no authorization or callback path. */
    @Bean
    fun socialClientRegistrationRepository(
        @Value("\${pawbridge.auth.google-enabled:false}") googleEnabled: Boolean,
        @Value("\${spring.security.oauth2.client.registration.google.client-id:\${GOOGLE_CLIENT_ID:}}") googleClientId: String,
        @Value("\${spring.security.oauth2.client.registration.google.client-secret:\${GOOGLE_SECRET_KEY:}}") googleSecret: String,
        @Value("\${spring.security.oauth2.client.registration.google.redirect-uri:\${REDIRECT_URI:}}") googleCallback: String,
        @Value("\${pawbridge.auth.naver-enabled:false}") naverEnabled: Boolean,
        @Value("\${spring.security.oauth2.client.registration.naver.client-id:\${NAVER_CLIENT_ID:}}") naverClientId: String,
        @Value("\${spring.security.oauth2.client.registration.naver.client-secret:\${NAVER_CLIENT_SECRET:}}") naverSecret: String,
        @Value("\${spring.security.oauth2.client.registration.naver.redirect-uri:\${NAVER_REDIRECT_URI:}}") naverCallback: String,
    ): InMemoryClientRegistrationRepository {
        val registrations = ArrayList<ClientRegistration>()
        if (googleEnabled) {
            registrations.add(google(googleClientId, googleSecret, googleCallback))
        }
        if (naverEnabled) {
            registrations.add(naver(naverClientId, naverSecret, naverCallback))
        }
        return InMemoryClientRegistrationRepository(registrations)
    }

    @Bean
    fun googleOAuth2UserService(login: SocialLogin): SocialOAuth2UserService {
        return SocialOAuth2UserService(login)
    }

    @Bean
    fun googleOAuth2SuccessHandler(
        login: SocialLogin,
        @Value("\${oauth2.redirect-uri:\${OAUTH2_REDIRECT_URI:}}") frontend: String,
    ): SocialOAuth2SuccessHandler {
        requireUri(frontend, null)
        return SocialOAuth2SuccessHandler(login, frontend)
    }

    @Bean
    fun googleOAuth2FailureHandler(
        @Value("\${oauth2.redirect-uri:\${OAUTH2_REDIRECT_URI:}}") frontend: String,
    ): SocialOAuth2FailureHandler {
        requireUri(frontend, null)
        return SocialOAuth2FailureHandler(frontend)
    }

    @Bean
    @Order(1)
    @Throws(Exception::class)
    fun googleOAuthSecurity(
        http: HttpSecurity,
        clients: InMemoryClientRegistrationRepository,
        users: SocialOAuth2UserService,
        success: SocialOAuth2SuccessHandler,
        failure: SocialOAuth2FailureHandler,
        @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) corsOriginPatterns: String,
    ): SecurityFilterChain {
        val securityContextRepository = RequestAttributeSecurityContextRepository()
        val authorizationRequestRepository = HttpSessionOAuth2AuthorizationRequestRepository()

        return http
            .securityMatcher(*oauthPaths(clients))
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { context ->
                context
                    .securityContextRepository(securityContextRepository)
                    .requireExplicitSave(true)
            }
            .authorizeHttpRequests { requests -> requests.anyRequest().permitAll() }
            // Same CORS policy as every other chain, so the policy cannot differ by which chain matches a path.
            .addFilterAt(GatewayCors.filter(corsOriginPatterns), CorsFilter::class.java)
            .oauth2Login { oauth ->
                oauth
                    .clientRegistrationRepository(clients)
                    .authorizationEndpoint { endpoint ->
                        endpoint.authorizationRequestRepository(authorizationRequestRepository)
                    }
                    .userInfoEndpoint { endpoint -> endpoint.userService(users) }
                    .successHandler(success)
                    .failureHandler(failure)
            }
            .build()
    }

    private fun oauthPaths(clients: InMemoryClientRegistrationRepository): Array<String> {
        val paths = ArrayList<String>()
        for (registration in clients) {
            paths.add("/oauth2/authorization/" + registration.registrationId)
            paths.add("/login/oauth2/code/" + registration.registrationId)
        }
        return paths.toTypedArray()
    }

    private companion object {
        const val NAVER_AUTHORIZATION_URI = "https://nid.naver.com/oauth2.0/authorize"
        const val NAVER_TOKEN_URI = "https://nid.naver.com/oauth2.0/token"
        const val NAVER_USER_INFO_URI = "https://openapi.naver.com/v1/nid/me"

        fun google(clientId: String, secret: String, callback: String): ClientRegistration {
            requireCredentials("Google", clientId, secret)
            requireUri(callback, "/login/oauth2/code/google")
            return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(secret)
                .scope("email", "profile")
                .redirectUri(callback)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth?prompt=select_account")
                .build()
        }

        /** Naver is not a Spring common provider; its user-info body nests the profile under "response". */
        fun naver(clientId: String, secret: String, callback: String): ClientRegistration {
            requireCredentials("Naver", clientId, secret)
            requireUri(callback, "/login/oauth2/code/naver")
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
                .build()
        }

        fun requireCredentials(provider: String, clientId: String, secret: String) {
            if (javaIsBlank(clientId) || javaIsBlank(secret)) {
                throw IllegalArgumentException("Explicit $provider client credentials required")
            }
        }

        fun requireUri(value: String, path: String?) {
            try {
                val uri = URI.create(value)
                if (!("https" == uri.scheme || "http" == uri.scheme) || uri.host == null ||
                    uri.rawUserInfo != null || uri.fragment != null || (path != null && path != uri.path)
                ) {
                    throw IllegalArgumentException()
                }
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("Explicit OAuth callback and frontend addresses required")
            }
        }
    }
}
