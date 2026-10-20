package com.pawbridge.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.application.SearchPosts;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.community.internal.post.web.PostSearchController;
import com.pawbridge.backend.community.internal.post.web.PostSearchExceptionHandler;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PostSearchController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {PostSearchController.class, PostSearchExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class PostSearchHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean SearchPosts searchPosts;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed"})
    void given__public_search_with_untrusted_identity__when__getting_results__then__preserve_raw_keyword_and_nine_fields(String bearer) throws Exception {
        String keyword = "  강아지  ";
        var created = LocalDateTime.of(2026, 10, 19, 0, 1, 2, 123456000);
        var updated = LocalDateTime.of(2026, 10, 19, 1, 2);
        when(searchPosts.search(keyword)).thenReturn(List.of(
                new PostDetails(42, 7, "작성자", "제목", "본문", "REPORT",
                        Arrays.asList("https://fixture.invalid/가.jpg", null, "https://fixture.invalid/가.jpg"), created, updated),
                new PostDetails(43, 8, null, "다른 글", "내용", "MISSING", null, created, updated)));
        var request = get("/api/v1/posts/search").param("keyword", keyword)
                .param("page", "invalid").param("size", "0").param("boardType", "COMMUNICATION")
                .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN");
        if (bearer.equals("malformed")) request.header("Authorization", "Bearer invalid");

        mvc.perform(request).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].length()").value(9))
                .andExpect(jsonPath("$.data[0].postId").value(42))
                .andExpect(jsonPath("$.data[0].authorId").value(7))
                .andExpect(jsonPath("$.data[0].authorNickname").value("작성자"))
                .andExpect(jsonPath("$.data[0].title").value("제목"))
                .andExpect(jsonPath("$.data[0].content").value("본문"))
                .andExpect(jsonPath("$.data[0].boardType").value("REPORT"))
                .andExpect(jsonPath("$.data[0].imageUrls.length()").value(3))
                .andExpect(jsonPath("$.data[0].imageUrls[0]").value("https://fixture.invalid/가.jpg"))
                .andExpect(jsonPath("$.data[0].imageUrls[1]").isEmpty())
                .andExpect(jsonPath("$.data[0].imageUrls[2]").value("https://fixture.invalid/가.jpg"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-19T00:01:02.123456"))
                .andExpect(jsonPath("$.data[0].updatedAt").value("2026-10-19T01:02:00"))
                .andExpect(jsonPath("$.data[1].length()").value(9))
                .andExpect(jsonPath("$.data[1].postId").value(43))
                .andExpect(jsonPath("$.data[1].authorId").value(8))
                .andExpect(jsonPath("$.data[1].boardType").value("MISSING"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"authorNickname\":null")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"imageUrls\":null")));
        verify(searchPosts).search(keyword);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__no_search_results__when__getting_results__then__return_empty_array_without_paging_envelope() throws Exception {
        when(searchPosts.search("일치없음")).thenReturn(List.of());

        mvc.perform(get("/api/v1/posts/search").param("keyword", "일치없음"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}", JsonCompareMode.STRICT));
        verify(searchPosts).search("일치없음");
    }

    @Test
    void given__missing_keyword__when__getting_results__then__preserve_required_parameter_400_without_business_call() throws Exception {
        mvc.perform(get("/api/v1/posts/search"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"필수 요청 파라미터 'keyword'(이)가 누락되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(searchPosts);
    }

    @Test
    void given__missing_post_on_existing_read_path__when__getting_post__then__search_advice_does_not_replace_original_404() throws Exception {
        when(readPost.read(42)).thenThrow(new PostNotFoundException());

        mvc.perform(get("/api/v1/posts/read/42"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(searchPosts, listPosts);
    }

    @ParameterizedTest
    @MethodSource("searchFailures")
    void given__search_failure_with_sensitive_values__when__getting_results__then__safe_scoped_error_without_secret_logs(
            int expectedStatus, String expectedMessage, RuntimeException failure) throws Exception {
        String keyword = "synthetic-private-keyword";
        when(searchPosts.search(keyword)).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(get("/api/v1/posts/search").param("keyword", keyword)
                            .header("Authorization", "Bearer synthetic-private-header"))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(content().json("{\"code\":" + expectedStatus + ",\"message\":\"" + expectedMessage + "\",\"data\":null}", JsonCompareMode.STRICT));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain(keyword, "synthetic-private-token", "synthetic-private-content",
                        "private-image", "synthetic-private-password", "synthetic-private-header", "jdbc:postgresql:");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(searchPosts).search(keyword);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> searchFailures() {
        var failure = new IllegalStateException(
                "Bearer synthetic-private-token; synthetic-private-content; https://fixture.invalid/private-image",
                new java.sql.SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        return Stream.of(
                Arguments.of(400, "검색 입력을 확인해 주세요.", new InvalidPostSearchInputException(failure)),
                Arguments.of(503, "검색 서비스를 사용할 수 없습니다.", new PostSearchUnavailableException(failure)),
                Arguments.of(500, "서버 에러!", failure));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/posts", "GET /api/v1/posts/search/42", "GET /api/v1/posts/search/",
            "GET /api/v1/posts/searching", "GET /api/posts/search",
            "HEAD /api/v1/posts/search", "OPTIONS /api/v1/posts/search", "POST /api/v1/posts/search", "PATCH /api/v1/posts/search"})
    void given__unexposed_method_or_adjacent_path__when__requesting__then__deny_without_search(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]).param("keyword", "강아지"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(searchPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void given__write_method_overlapping_search_path_without_token__when__requesting__then__preserve_write_authentication(String method) throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/v1/posts/search"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(searchPosts);
    }
}
