package com.pawbridge.backend.boot.configuration;

import jakarta.servlet.DispatcherType;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
public class ReadSecurityConfiguration {
    @Bean
    SecurityFilterChain postReadSecurity(HttpSecurity http, PostTokenAuthenticator tokens,
            @Value("${pawbridge.visits.enabled:false}") boolean visitsEnabled,
            @Value("${pawbridge.visits.collection-enabled:false}") boolean collectionEnabled,
            @Value("${pawbridge.auth.sessions-enabled:false}") boolean sessionsEnabled) throws Exception {

        var postCreation = new AntPathRequestMatcher("/api/v1/posts", "POST");
        var postDeletion = new AntPathRequestMatcher("/api/v1/posts/*", "DELETE");
        var postUpdate = new AntPathRequestMatcher("/api/v1/posts/*", "PUT");
        var commentCreation = new AntPathRequestMatcher("/api/v1/comments/posts/{postId}", "POST");
        var commentDeletion = new AntPathRequestMatcher("/api/v1/comments/{commentId}", "DELETE");
        var commentUpdate = new AntPathRequestMatcher("/api/v1/comments/{commentId}", "PUT");
        var userProfile = new AntPathRequestMatcher("/api/v1/users/me", "GET");
        var userNickname = new AntPathRequestMatcher("/api/v1/users/me/nickname", "PUT");
        var favoriteStatus = new AntPathRequestMatcher("/api/v1/favorites/{animalId}/check", "GET");
        var noteRecipient = new AntPathRequestMatcher("/api/v1/notes/recipients/{member}", "GET");
        var noteBlockStatus = new AntPathRequestMatcher("/api/v1/notes/blocks/status", "GET");
        var noteBlockList = new AntPathRequestMatcher("/api/v1/notes/blocks", "GET");
        var noteBlockRemove = new AntPathRequestMatcher("/api/v1/notes/blocks/{member}", "DELETE");
        var noteBlockCreate = new AntPathRequestMatcher("/api/v1/notes/blocks/{member}", "PUT");
        var noteNotifications = new AntPathRequestMatcher("/api/v1/notes/notifications", "GET");
        var privateNoteList = new AntPathRequestMatcher("/api/v1/notes", "GET");
        var noteStream = new AntPathRequestMatcher("/api/v1/notes/stream", "GET");
        var noteRead = new AntPathRequestMatcher("/api/v1/notes/{id}/read", "PUT");
        var noteFavorite = new AntPathRequestMatcher("/api/v1/notes/{id}/favorite", "PUT");
        var noteSend = new AntPathRequestMatcher("/api/v1/notes", "POST");
        var noteDeletion = new AntPathRequestMatcher("/api/v1/notes/{id}", "DELETE");
        var privateNote = new AndRequestMatcher(
                new AntPathRequestMatcher("/api/v1/notes/{id}", "GET"),
                new NegatedRequestMatcher(new AntPathRequestMatcher("/api/v1/notes/stream", "GET")));
        var totalUserCount = new AntPathRequestMatcher("/api/v1/admin/users/stats/total-users", "GET");
        var dailySignupStats = new AntPathRequestMatcher("/api/v1/admin/users/stats/daily-signups", "GET");
        var signupPeriods = new AntPathRequestMatcher("/api/v1/admin/users/stats/signup-periods", "GET");
        var userDeletion = new AntPathRequestMatcher("/api/v1/admin/users/{userId}", "DELETE");
        var userDetail = new AndRequestMatcher(
                new AntPathRequestMatcher("/api/v1/admin/users/{userId}", "GET"),
                new NegatedRequestMatcher(new AntPathRequestMatcher("/api/v1/admin/users/search", "GET")));
        var userUpdate = new AntPathRequestMatcher("/api/v1/admin/users/{userId}", "PUT");
        var sessionEnabled=(org.springframework.security.web.util.matcher.RequestMatcher) request -> sessionsEnabled;
        var sessionRefresh=new AndRequestMatcher(sessionEnabled,new AntPathRequestMatcher("/api/v1/auth/refresh","POST"));
        var sessionLogout=new AndRequestMatcher(sessionEnabled,new AntPathRequestMatcher("/api/v1/auth/logout","POST"));
        var visitCollectionEnabled = (org.springframework.security.web.util.matcher.RequestMatcher)
                request -> visitsEnabled && collectionEnabled;
        var visitIdentity = new AndRequestMatcher(visitCollectionEnabled,
                new AntPathRequestMatcher("/api/v1/visits/identity", "POST"));
        var visitCount = new AndRequestMatcher(visitCollectionEnabled,
                new AntPathRequestMatcher("/api/v1/visits/today", "POST"));
        var visitPeriod = new AndRequestMatcher(visitCollectionEnabled,
                new AntPathRequestMatcher("/api/v1/admin/visits/stats/period", "GET"));
        var adminRoutes = new OrRequestMatcher(totalUserCount, dailySignupStats, signupPeriods, userDeletion, userDetail, userUpdate, visitPeriod);
        var postWriting = new OrRequestMatcher(postCreation, postDeletion, postUpdate, commentCreation, commentDeletion, commentUpdate, userProfile, userNickname, favoriteStatus, noteRecipient, noteBlockStatus, totalUserCount, dailySignupStats, signupPeriods, noteBlockList, noteNotifications, privateNote, privateNoteList, noteBlockRemove, noteBlockCreate, noteStream, noteRead, noteFavorite, noteDeletion, noteSend, userDeletion, userDetail, userUpdate, visitPeriod, sessionLogout);
        var streamContexts = new RequestAttributeSecurityContextRepository();
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(streamContexts).requireExplicitSave(true))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/read", "/api/v1/posts/read/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/home/videos").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/visits/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/comments/posts/read/{postId}").permitAll()
                        .requestMatchers(visitIdentity, visitCount).permitAll()
                        .requestMatchers(sessionRefresh).permitAll()
                        .requestMatchers(adminRoutes).hasAuthority("ROLE_ADMIN")
                        .requestMatchers(postWriting).authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new PostBearerAuthenticationFilter(tokens, postWriting, noteStream, streamContexts), AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> forbidden(response))
                        .accessDeniedHandler((request, response, exception) -> forbidden(response,
                                adminRoutes.matches(request) ? "관리자 권한이 필요합니다." : "접근할 수 없습니다.")))
                .build();
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        forbidden(response, "접근할 수 없습니다.");
    }

    private static void forbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(403);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}
