package com.pawbridge.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion;
import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException;
import com.pawbridge.backend.community.internal.comment.application.CreateComment;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler;
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController;
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler;
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

@WebMvcTest(controllers = CommentDeleteController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {CommentDeleteController.class, CommentDeleteExceptionHandler.class,
        CommentCreateController.class, CommentCreateExceptionHandler.class,
        CommentReadController.class, CommentReadExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class CommentDeleteHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean CommentDeletion deletion;
    @MockitoBean CreateComment createComment;
    @MockitoBean ListComments listComments;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__verified_owner_with_spoofed_headers__when__deleting_comment__then__use_principal_and_original_null_envelope(String role) throws Exception {
        mvc.perform(delete("/api/v1/comments/12").header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN").param("authorId", "999"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"댓글이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(deletion).delete(12, 7);
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "{\"content\":null,\"authorId\":999}"})
    void given__unneeded_or_broken_json_body__when__deleting_comment__then__ignore_body_without_new_validation(String body) throws Exception {
        mvc.perform(authenticatedDelete("12").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"댓글이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(deletion).delete(12, 7);
        verifyNoInteractions(createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_untrusted_token_and_bad_id__when__deleting_comment__then__authenticate_before_binding_or_deletion(String authorization, String message) throws Exception {
        var request = delete("/api/v1/comments/not-a-number").contentType(MediaType.APPLICATION_JSON).content("{")
                .header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(deletion, createComment, listComments, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> rejectedAuthorizations() {
        String expired = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        var otherKey = Keys.hmacShaKeyFor("f10-synthetic-other-signing-key-only-012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(otherKey).compact();
        String refresh = Jwts.builder().subject("synthetic@example.invalid")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(F3TestInputs.KEY).compact();
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Basic ignored", "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + expired, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + forged, "유효하지 않거나 만료된 토큰입니다."),
                Arguments.of("Bearer " + refresh, "토큰 파싱에 실패했습니다."));
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    void given__signed_invalid_identity__when__deleting_comment__then__reject_before_binding_or_deletion(Map<String, Object> claims) throws Exception {
        String token = Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();

        mvc.perform(delete("/api/v1/comments/not-a-number").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("토큰 파싱에 실패했습니다."));
        verifyNoInteractions(deletion, createComment, listComments, readPost, listPosts);
    }

    @Test
    void given__two_owners_then_anonymous_request__when__deleting_comments__then__clear_previous_identity() throws Exception {
        for (long userId : new long[] {7, 8}) {
            mvc.perform(delete("/api/v1/comments/12").header("Authorization", "Bearer " + F3TestInputs.token(userId, "ROLE_USER")))
                    .andExpect(status().isOk());
        }
        mvc.perform(delete("/api/v1/comments/12")).andExpect(status().isUnauthorized());
        verify(deletion).delete(12, 7);
        verify(deletion).delete(12, 8);
        verifyNoMoreInteractions(deletion);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {12, 0, -1})
    void given__unavailable_comment_from_port__when__deleting__then__preserve_404_without_new_id_validation(long commentId) throws Exception {
        doThrow(new CommentNotFoundException()).when(deletion).delete(commentId, 7);

        mvc.perform(authenticatedDelete(Long.toString(commentId))).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"댓글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(deletion).delete(commentId, 7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__another_authors_comment_for_any_role__when__deleting__then__preserve_owner_403_without_admin_override(String role) throws Exception {
        doThrow(new CommentNotOwnedException()).when(deletion).delete(12, 7);

        mvc.perform(delete("/api/v1/comments/12").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":403,\"message\":\"댓글에 접근할 권한이 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(deletion).delete(12, 7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9223372036854775808"})
    void given__unparseable_comment_id_with_valid_identity__when__deleting__then__preserve_legacy_safe_500(String commentId) throws Exception {
        mvc.perform(authenticatedDelete(commentId)).andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(deletion, createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @MethodSource("deletionFailures")
    void given__failure_after_authentication__when__deleting__then__safe_error_is_not_401_and_sensitive_details_are_not_logged(int expectedStatus, String message, RuntimeException failure) throws Exception {
        doThrow(failure).when(deletion).delete(12, 7);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(authenticatedDelete("12").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"synthetic-private-comment\"}"))
                    .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedStatus))
                    .andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.data").isEmpty());
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-private-token", "synthetic-private-comment",
                        "synthetic-private-password", "synthetic-private-url", "Bearer ", "jdbc:postgresql:");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(deletion).delete(12, 7);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> deletionFailures() {
        var failure = new IllegalStateException("Bearer synthetic-private-token; synthetic-private-comment",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new RuntimeException("https://fixture.invalid/synthetic-private-url"));
        return Stream.of(Arguments.of(400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", new UnsupportedCommentCharactersException(failure)),
                Arguments.of(500, "서버 에러!", failure));
    }

    @Test
    void given__existing_comment_read_create_and_post_404__when__requesting__then__delete_authentication_and_advice_remain_scoped() throws Exception {
        var time = LocalDateTime.of(2026, 10, 19, 12, 30);
        var comment = new CommentDetails(12, 42, 7, "작성자", "댓글", time, time);
        when(listComments.read(42)).thenReturn(List.of(comment));
        when(createComment.create(42, 7, "댓글")).thenReturn(comment);
        when(readPost.read(42)).thenThrow(new PostNotFoundException());

        mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].commentId").value(12));
        mvc.perform(post("/api/v1/comments/posts/42").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"댓글\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("댓글이 생성되었습니다."));
        mvc.perform(get("/api/v1/posts/read/42")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(deletion, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/comments/12", "POST /api/v1/comments/12", "PUT /api/v1/comments/12",
            "PATCH /api/v1/comments/12", "HEAD /api/v1/comments/12", "OPTIONS /api/v1/comments/12",
            "DELETE /api/v1/comments", "DELETE /api/v1/comments/", "DELETE /api/v1/comments/12/",
            "DELETE /api/v1/comments/12/extra", "DELETE /api/v1/comments/posts/42",
            "DELETE /api/v1/comments/posts/read/42", "DELETE /api/comments/12", "DELETE /api/v1/admin/comments/12"})
    void given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_business_calls(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(deletion, createComment, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/posts", "PUT /api/v1/posts/42", "DELETE /api/v1/posts/42", "POST /api/v1/comments/posts/42"})
    void given__existing_write_without_token__when__requesting__then__preserve_jwt_authentication(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(deletion, createComment, listComments, readPost, listPosts);
    }

    private static MockHttpServletRequestBuilder authenticatedDelete(String commentId) {
        return delete("/api/v1/comments/" + commentId).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }
}
