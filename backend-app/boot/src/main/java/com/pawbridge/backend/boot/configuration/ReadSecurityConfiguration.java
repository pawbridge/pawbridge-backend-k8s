package com.pawbridge.backend.boot.configuration;

import jakarta.servlet.DispatcherType;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class ReadSecurityConfiguration {
    @Bean
    SecurityFilterChain postReadSecurity(HttpSecurity http, PostTokenAuthenticator tokens) throws Exception {
        var postCreation = new AntPathRequestMatcher("/api/v1/posts", "POST");
        var postDeletion = new AntPathRequestMatcher("/api/v1/posts/*", "DELETE");
        var postUpdate = new AntPathRequestMatcher("/api/v1/posts/*", "PUT");
        var commentCreation = new AntPathRequestMatcher("/api/v1/comments/posts/{postId}", "POST");
        var commentDeletion = new AntPathRequestMatcher("/api/v1/comments/{commentId}", "DELETE");
        var commentUpdate = new AntPathRequestMatcher("/api/v1/comments/{commentId}", "PUT");
        var userProfile = new AntPathRequestMatcher("/api/v1/users/me", "GET");
        var postWriting = new OrRequestMatcher(postCreation, postDeletion, postUpdate, commentCreation, commentDeletion, commentUpdate, userProfile);
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/read", "/api/v1/posts/read/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/comments/posts/read/{postId}").permitAll()
                        .requestMatchers(postWriting).authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new PostBearerAuthenticationFilter(tokens, postWriting), AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> forbidden(response))
                        .accessDeniedHandler((request, response, exception) -> forbidden(response)))
                .build();
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}");
    }
}
