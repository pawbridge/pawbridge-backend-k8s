package com.pawbridge.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.CreateComment;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler;
import com.pawbridge.backend.community.internal.comment.web.CommentReadController;
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CommentCreateController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {CommentCreateController.class, CommentCreateExceptionHandler.class,
        CommentReadController.class, CommentReadExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class CommentCreateHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CreateComment createComment;
    @MockitoBean ListComments listComments;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__signed_writer_and_spoofed_identity__when__creating_comment__then__use_verified_id_and_original_seven_fields(String role) throws Exception {
        when(createComment.create(42, 7, "  댓글 본문  ")).thenReturn(details(7, "  댓글 본문  "));

        mvc.perform(post("/api/v1/comments/posts/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  댓글 본문  \",\"authorId\":999,\"postId\":999}")
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("댓글이 생성되었습니다."))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.commentId").value(12))
                .andExpect(jsonPath("$.data.postId").value(42))
                .andExpect(jsonPath("$.data.authorId").value(7))
                .andExpect(jsonPath("$.data.authorNickname").value("작성자"))
                .andExpect(jsonPath("$.data.content").value("  댓글 본문  "))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-19T14:01:02.123456789"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-19T14:01:02.123456789"));
        verify(createComment).create(42, 7, "  댓글 본문  ");
        verifyNoInteractions(listComments, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("acceptedContents")
    void given__legacy_nonblank_content__when__creating_comment__then__preserve_unicode_whitespace_raw_content_and_no_maximum(String content) throws Exception {
        when(createComment.create(42, 7, content)).thenReturn(details(7, content));

        mvc.perform(authenticatedPost("42").content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value(content));
        verify(createComment).create(42, 7, content);
    }

    private static Stream<String> acceptedContents() {
        return Stream.of("\u2003", "\u3000", " \t본문\n ", "긴".repeat(100_000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\" \"}", "{\"content\":\" \\t\\r\\n \"}"})
    void given__missing_null_or_ascii_blank_content__when__creating_comment__then__legacy_validation_returns_400_without_business_call(String body) throws Exception {
        mvc.perform(authenticatedPost("42").content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"content: 내용은 필수입니다\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "[]"})
    void given__missing_or_malformed_json_body__when__creating_comment__then__return_legacy_safe_400_without_business_call(String body) throws Exception {
        mvc.perform(authenticatedPost("42").content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_untrusted_token_and_bad_request__when__creating_comment__then__authenticate_before_binding_or_business_calls(String authorization, String message) throws Exception {
        var request = post("/api/v1/comments/posts/not-a-number").contentType(MediaType.APPLICATION_JSON).content("{")
                .header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().json(mapper.writeValueAsString(new CommentReadController.Response<>(401, message, null)), JsonCompareMode.STRICT));
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> rejectedAuthorizations() {
        String expired = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        var otherKey = Keys.hmacShaKeyFor("f9-synthetic-other-signing-key-only-012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(otherKey).compact();
        String refresh = Jwts.builder().subject("synthetic@example.invalid")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(F3TestInputs.KEY).compact();
        return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + expired, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + forged, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + refresh, "토큰 파싱에 실패했습니다."));
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    void given__signed_invalid_identity__when__creating_comment__then__reject_before_json_validation_or_business_call(Map<String, Object> claims) throws Exception {
        String token = Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();

        mvc.perform(post("/api/v1/comments/posts/42").contentType(MediaType.APPLICATION_JSON).content("{")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":401,\"message\":\"토큰 파싱에 실패했습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    @Test
    void given__two_writers_then_anonymous_request__when__creating_comments__then__clear_previous_identity() throws Exception {
        when(createComment.create(eq(42L), anyLong(), eq("댓글")))
                .thenAnswer(call -> details(call.getArgument(1), "댓글"));
        for (long userId : new long[] {7, 8}) {
            mvc.perform(post("/api/v1/comments/posts/42").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"댓글\"}")
                            .header("Authorization", "Bearer " + F3TestInputs.token(userId, "ROLE_USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.authorId").value((int) userId));
        }
        mvc.perform(post("/api/v1/comments/posts/42").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"댓글\"}"))
                .andExpect(status().isUnauthorized());
        verify(createComment).create(42, 7, "댓글");
        verify(createComment).create(42, 8, "댓글");
        verifyNoMoreInteractions(createComment);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9223372036854775808"})
    void given__unparseable_post_id_with_valid_identity__when__creating_comment__then__preserve_legacy_safe_500(String postId) throws Exception {
        mvc.perform(authenticatedPost(postId).content("{\"content\":\"댓글\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void given__nonpositive_post_id__when__creating_comment__then__let_parent_lookup_decide_404(long postId) throws Exception {
        when(createComment.create(postId, 7, "댓글")).thenThrow(new PostNotFoundException());

        mvc.perform(authenticatedPost(Long.toString(postId)).content("{\"content\":\"댓글\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(createComment).create(postId, 7, "댓글");
    }

    @ParameterizedTest
    @MethodSource("businessFailures")
    void given__failure_after_authentication__when__creating_comment__then__preserve_safe_error_contract_without_secret_logs(int status, String message, RuntimeException failure) throws Exception {
        when(createComment.create(42, 7, "synthetic-private-comment")).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(authenticatedPost("42").content("{\"content\":\"synthetic-private-comment\"}"))
                    .andExpect(status().is(status))
                    .andExpect(content().json(mapper.writeValueAsString(new CommentReadController.Response<>(status, message, null)), JsonCompareMode.STRICT));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-private-token", "synthetic-private-comment",
                        "synthetic-private-password", "synthetic-private-url", "Bearer ", "jdbc:postgresql:");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(createComment).create(42, 7, "synthetic-private-comment");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> businessFailures() {
        var failure = new IllegalStateException("Bearer synthetic-private-token; synthetic-private-comment",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new RuntimeException("https://fixture.invalid/synthetic-private-url"));
        return Stream.of(Arguments.of(404, "게시글을 찾을 수 없습니다.", new PostNotFoundException()),
                Arguments.of(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", new UnsupportedCommentCharactersException(failure)),
                Arguments.of(500, "서버 에러!", failure));
    }

    @Test
    void given__existing_public_reads_and_post_404__when__reading__then__comment_create_authentication_and_advice_remain_scoped() throws Exception {
        when(listComments.read(42)).thenReturn(List.of(details(7, "댓글")));
        when(readPost.read(42)).thenThrow(new PostNotFoundException());

        mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].commentId").value(12));
        mvc.perform(get("/api/v1/posts/read/42")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(createComment, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/comments/posts/42", "PUT /api/v1/comments/posts/42", "DELETE /api/v1/comments/posts/42",
            "HEAD /api/v1/comments/posts/42", "OPTIONS /api/v1/comments/posts/42", "PATCH /api/v1/comments/posts/42",
            "POST /api/v1/comments/posts", "POST /api/v1/comments/posts/", "POST /api/v1/comments/posts/42/",
            "POST /api/v1/comments/posts/42/extra", "POST /api/v1/comments/posts/read/42", "POST /api/comments/posts/42",
            "POST /api/v1/admin/comments/posts/42", "PUT /api/v1/comments/12"})
    void given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_business_calls(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/posts", "PUT /api/v1/posts/42", "DELETE /api/v1/posts/42"})
    void given__existing_post_write_without_token__when__requesting__then__preserve_jwt_authentication(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    private static MockHttpServletRequestBuilder authenticatedPost(String postId) {
        return post("/api/v1/comments/posts/" + postId).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }

    private static CommentDetails details(long authorId, String content) {
        var created = LocalDateTime.of(2026, 10, 19, 14, 1, 2, 123456789);
        return new CommentDetails(12, 42, authorId, "작성자", content, created, created);
    }
}
