package com.pawbridge.backend.boot.configuration;

import jakarta.servlet.DispatcherType;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
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
            @Value("${pawbridge.auth.sessions-enabled:false}") boolean sessionsEnabled,
            @Value("${pawbridge.chat.enabled:${MEMBER_CHAT_ENABLED:false}}") boolean chatEnabledFlag,
            @Value("${pawbridge.shelter-membership.enabled:false}") boolean shelterMembershipEnabled,
            @Value("${pawbridge.user-animal-activities.enabled:false}") boolean animalActivitiesEnabled,
            @Value("${pawbridge.home-videos.management-enabled:false}") boolean videoManagementEnabled,
            @Value("${pawbridge.animal-reports.enabled:false}") boolean reportsEnabledFlag,
            @Value("${pawbridge.user-store-mypage.enabled:false}") boolean storeMyPageEnabledFlag,
            @Value("${pawbridge.animal-discovery.enabled:false}") boolean animalDiscoveryEnabledFlag,
            @Value("${pawbridge.animal-recommendations.enabled:false}") boolean animalRecommendationsEnabledFlag) throws Exception {

        var animalDiscoveryEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->animalDiscoveryEnabledFlag;
        var animalDiscoveryReading=new AndRequestMatcher(animalDiscoveryEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/animals","GET"),
                new RegexRequestMatcher("^/api/v1/animals/[0-9]+(?:\\?.*)?$","GET")));
        var animalRecommendationsEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->animalRecommendationsEnabledFlag;
        var animalRecommendationReading=new AndRequestMatcher(animalRecommendationsEnabled,
                new RegexRequestMatcher("^/api/v1/animals/[0-9]+/similar(?:\\?.*)?$","GET"));

        var storeMyPageEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->storeMyPageEnabledFlag;
        var storeMyPage=new AndRequestMatcher(storeMyPageEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/users/me/wishlists","GET"),
                new AntPathRequestMatcher("/api/v1/users/me/orders","GET"),
                new AntPathRequestMatcher("/api/v1/users/me/cart","GET")));
        var reportsEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->reportsEnabledFlag;
        var reportReading=new AndRequestMatcher(reportsEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/reports","GET"),
                new AntPathRequestMatcher("/api/v1/reports/{reportId}","GET")));
        var reportWriting=new AndRequestMatcher(reportsEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/reports","POST"),
                new AntPathRequestMatcher("/api/v1/reports/{reportId}","PUT"),
                new AntPathRequestMatcher("/api/v1/reports/{reportId}","DELETE")));
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
        var userDirectory = new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/admin/users", "GET"),
                new AntPathRequestMatcher("/api/v1/admin/users/search", "GET"));
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
        var chatEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->chatEnabledFlag;
        var chatSocket=new AndRequestMatcher(chatEnabled,new AntPathRequestMatcher("/api/v1/chats/socket","GET"));
        // Keep the original 36-character UUID path constraint; query strings do not widen path matching.
        var chatRest=new AndRequestMatcher(chatEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/chats","GET"),
                new AntPathRequestMatcher("/api/v1/chats/notifications","GET"),
                new AntPathRequestMatcher("/api/v1/chats/with/{member}","GET"),
                new AntPathRequestMatcher("/api/v1/chats/connection-ticket","POST"),
                new RegexRequestMatcher("^/api/v1/chats/[0-9a-fA-F-]{36}(?:/messages)?(?:\\?.*)?$","GET"),
                new RegexRequestMatcher("^/api/v1/chats/[0-9a-fA-F-]{36}/read(?:\\?.*)?$","PUT"),
                new RegexRequestMatcher("^/api/v1/chats/[0-9a-fA-F-]{36}/visibility(?:\\?.*)?$","DELETE")));
        var homeVideoEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->videoManagementEnabled;
        var homeVideoManagement=new AndRequestMatcher(homeVideoEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/admin/videos","GET"),
                new AntPathRequestMatcher("/api/v1/admin/videos/preview","POST"),
                new AntPathRequestMatcher("/api/v1/admin/videos","POST"),
                new AntPathRequestMatcher("/api/v1/admin/videos/{id}","PUT"),
                new AntPathRequestMatcher("/api/v1/admin/videos/{id}/publication","PUT"),
                new AntPathRequestMatcher("/api/v1/admin/videos/order","PUT"),
                new AntPathRequestMatcher("/api/v1/admin/videos/{id}/recheck","POST")));
        var adminPostList = new AntPathRequestMatcher("/api/v1/admin/posts", "GET");
        var adminPostDetail = new AntPathRequestMatcher("/api/v1/admin/posts/{postId}", "GET");
        var adminPostUpdate = new AntPathRequestMatcher("/api/v1/admin/posts/{postId}", "PUT");
        var adminPostDelete = new AntPathRequestMatcher("/api/v1/admin/posts/{postId}", "DELETE");
        var postStatsToday = new AntPathRequestMatcher("/api/v1/admin/posts/stats/today", "GET");
        var postStatsPeriod = new AntPathRequestMatcher("/api/v1/admin/posts/stats/period", "GET");
        var animalActivities=(org.springframework.security.web.util.matcher.RequestMatcher)request -> animalActivitiesEnabled;
        var userAnimalActivities=new AndRequestMatcher(animalActivities,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/favorites/{animalId}","POST"),
                new AntPathRequestMatcher("/api/v1/favorites/{animalId}","DELETE"),
                new AntPathRequestMatcher("/api/v1/users/me/favorite-animals","GET"),
                new AntPathRequestMatcher("/api/v1/users/me/registered-animals","GET")));
        var shelterEnabled=(org.springframework.security.web.util.matcher.RequestMatcher)request->shelterMembershipEnabled;
        var shelterOwn=new AndRequestMatcher(shelterEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/users/me/shelter-applications","GET"),
                new AntPathRequestMatcher("/api/v1/users/me/shelter-applications","POST")));
        var shelterAdmin=new AndRequestMatcher(shelterEnabled,new OrRequestMatcher(
                new AntPathRequestMatcher("/api/v1/admin/users/shelter-applications","GET"),
                new AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/stats","GET"),
                new AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/{id}","GET"),
                new AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/{id}/approve","POST"),
                new AntPathRequestMatcher("/api/v1/admin/users/shelter-applications/{id}/reject","POST"),
                new AntPathRequestMatcher("/api/v1/admin/users/shelters/{careRegNo}/members","GET")));
        var adminRoutes = new OrRequestMatcher(homeVideoManagement, shelterAdmin, userDirectory, adminPostList, adminPostDetail, adminPostUpdate, adminPostDelete, postStatsToday, postStatsPeriod,totalUserCount, dailySignupStats, signupPeriods, userDeletion, userDetail, userUpdate, visitPeriod);
        var postWriting = new OrRequestMatcher(storeMyPage, reportWriting, homeVideoManagement, userAnimalActivities, shelterOwn, shelterAdmin, userDirectory, chatRest, adminPostList, adminPostDetail, adminPostUpdate, adminPostDelete, postStatsToday, postStatsPeriod,postCreation, postDeletion, postUpdate, commentCreation, commentDeletion, commentUpdate, userProfile, userNickname, favoriteStatus, noteRecipient, noteBlockStatus, totalUserCount, dailySignupStats, signupPeriods, noteBlockList, noteNotifications, privateNote, privateNoteList, noteBlockRemove, noteBlockCreate, noteStream, noteRead, noteFavorite, noteDeletion, noteSend, userDeletion, userDetail, userUpdate, visitPeriod, sessionLogout);
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
                        .requestMatchers(animalDiscoveryReading, animalRecommendationReading).permitAll()
                        .requestMatchers(reportReading).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/read", "/api/v1/posts/read/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/home/videos").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/visits/today").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/comments/posts/read/{postId}").permitAll()
                        .requestMatchers(visitIdentity, visitCount).permitAll()
                        .requestMatchers(sessionRefresh).permitAll()
                        .requestMatchers(chatSocket).permitAll()
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
