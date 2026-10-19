package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(controllers = HomeVideoController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {HomeVideoController.class, HomeVideoExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class HomeVideoHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReadHomeVideos videos;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer invalid"})
    void given__anonymous_or_bad_bearer__when__reading_home_videos__then__return_exact_eleven_fields_nulls_and_microsecond_instants(String authorization) throws Exception {
        when(videos.read()).thenReturn(List.of(
                new HomeVideo(UUID.fromString("00000000-0000-0000-0000-000000000001"), "abcdefghijk", true, -1,
                        Instant.parse("2026-10-01T03:04:05.123456Z"), null, "", null, null, true,
                        Instant.parse("2026-10-19T05:06:07.654321Z")),
                new HomeVideo(UUID.fromString("00000000-0000-0000-0000-000000000002"), "lmnopqrstuv", true, 0,
                        Instant.parse("2026-10-02T03:04:05.000001Z"), "", "기관", "https://images.fixture.invalid/video.jpg",
                        2_147_483_648L, true, Instant.parse("2026-10-19T05:06:08.000001Z"))));
        var request = get("/api/v1/home/videos").header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN");
        if (!authorization.isEmpty()) request.header("Authorization", authorization);

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("""
                        {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":[
                          {"id":"00000000-0000-0000-0000-000000000001","videoId":"abcdefghijk","published":true,"position":-1,
                           "createdAt":"2026-10-01T03:04:05.123456Z","title":null,"channelTitle":"","thumbnailUrl":null,
                           "durationSeconds":null,"available":true,"checkedAt":"2026-10-19T05:06:07.654321Z"},
                          {"id":"00000000-0000-0000-0000-000000000002","videoId":"lmnopqrstuv","published":true,"position":0,
                           "createdAt":"2026-10-02T03:04:05.000001Z","title":"","channelTitle":"기관",
                           "thumbnailUrl":"https://images.fixture.invalid/video.jpg","durationSeconds":2147483648,
                           "available":true,"checkedAt":"2026-10-19T05:06:08.000001Z"}
                        ]}
                        """, JsonCompareMode.STRICT));

        verify(videos).read();
        verifyNoInteractions(readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__no_visible_videos__when__reading_home__then__return_successful_empty_array() throws Exception {
        when(videos.read()).thenReturn(List.of());

        mvc.perform(get("/api/v1/home/videos"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}", JsonCompareMode.STRICT));

        verify(videos).read();
    }

    @ParameterizedTest
    @MethodSource("readFailures")
    void given__query_transaction_or_runtime_failure__when__reading_home__then__return_only_safe_503_envelope(RuntimeException failure) throws Exception {
        when(videos.read()).thenThrow(failure);

        mvc.perform(get("/api/v1/home/videos").header("Authorization", "Bearer synthetic-private-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":503,\"message\":\"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.\",\"data\":null}", JsonCompareMode.STRICT));

        verify(videos).read();
        verifyNoInteractions(readPost, listPosts);
    }

    static Stream<RuntimeException> readFailures() {
        var cause = new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password");
        return Stream.of(new DataAccessResourceFailureException("synthetic-private-query", cause),
                new TransactionSystemException("synthetic-private-transaction", cause),
                new IllegalStateException("synthetic-private-runtime", cause));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/home", "GET /api/v1/home/videos/", "GET /api/v1/home/videos/extra",
            "GET /api/home/videos", "GET /api/v1/admin/videos", "POST /api/v1/home/videos",
            "PUT /api/v1/home/videos", "DELETE /api/v1/home/videos", "HEAD /api/v1/home/videos", "OPTIONS /api/v1/home/videos"})
    void given__unexposed_method_or_adjacent_path__when__requesting__then__deny_before_home_video_read(String request) throws Exception {
        String[] parts = request.split(" ", 2);

        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]))
                .andExpect(status().isForbidden());

        verifyNoInteractions(videos, readPost, listPosts);
    }

    @Test
    void given__protected_favorite_get_and_existing_post_failure__when__requesting__then__home_advice_and_public_matcher_remain_scoped() throws Exception {
        when(readPost.read(42)).thenThrow(new IllegalStateException("synthetic-private-post"));

        mvc.perform(get("/api/v1/favorites/42/check"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":401,\"message\":\"인증 토큰이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));

        verify(readPost).read(42);
        verifyNoInteractions(videos, listPosts);
    }
}
