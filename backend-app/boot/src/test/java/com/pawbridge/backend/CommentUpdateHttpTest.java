package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion;
import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.CommentModification;
import com.pawbridge.backend.community.internal.comment.application.CommentReader;
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot;
import com.pawbridge.backend.community.internal.comment.application.CreateComment;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.application.UpdateComment;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler;
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController;
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler;
import com.pawbridge.backend.community.internal.comment.web.CommentReadController;
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler;
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController;
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
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

@WebMvcTest(controllers = CommentUpdateController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {CommentUpdateController.class, CommentUpdateExceptionHandler.class, UpdateComment.class,
        CommentCreateController.class, CommentCreateExceptionHandler.class,
        CommentDeleteController.class, CommentDeleteExceptionHandler.class,
        CommentReadController.class, CommentReadExceptionHandler.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class CommentUpdateHttpTest {
    private static final LocalDateTime CREATED = LocalDateTime.parse("2026-10-18T11:01:02.123456789");
    private static final LocalDateTime UPDATED = LocalDateTime.parse("2026-10-19T12:03:04.987654321");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CommentReader comments;
    @MockitoBean CommentModification modification;
    @MockitoBean UserNicknameQuery users;
    @MockitoBean CreateComment createComment;
    @MockitoBean CommentDeletion deletion;
    @MockitoBean ListComments listComments;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @BeforeEach
    void prepareActiveCommentAndNickname() {
        when(comments.findById(12)).thenReturn(Optional.of(snapshot(7, "원본")));
        when(users.findNickname(7)).thenReturn(Optional.of("작성자"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__verified_owner_and_spoofed_identity__when__updating_comment__then__return_new_content_with_original_seven_fields_and_old_dates(String role) throws Exception {
        mvc.perform(put("/api/v1/comments/12").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .content("{\"content\":\"  수정 본문  \",\"authorId\":999,\"postId\":999,\"commentId\":999}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(content().json(mapper.writeValueAsString(new CommentUpdateController.Response<>(200,
                        "댓글이 수정되었습니다.", details(7, "  수정 본문  ", "작성자"))), JsonCompareMode.STRICT));

        var order = inOrder(comments, users, modification);
        order.verify(comments).findById(12);
        order.verify(users).findNickname(7);
        order.verify(modification).update(12, "  수정 본문  ");
        verifyNoMoreInteractions(comments, users, modification);
        verifyNoInteractions(createComment, deletion, listComments, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("acceptedContents")
    void given__legacy_nonblank_content__when__updating_comment__then__preserve_unicode_whitespace_raw_content_and_no_maximum(String content) throws Exception {
        mvc.perform(authenticatedPut("12").content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value(content))
                .andExpect(jsonPath("$.data.updatedAt").value(UPDATED.toString()));
        verify(modification).update(12, content);
    }

    private static Stream<String> acceptedContents() {
        return Stream.of("\u2003", "\u3000", " \t수정 본문\n ", "긴".repeat(100_000));
    }

    @Test
    void given__same_content__when__updating_comment__then__still_resolve_nickname_and_return_old_dates_without_modification() throws Exception {
        mvc.perform(authenticatedPut("12").content("{\"content\":\"원본\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(content().json(mapper.writeValueAsString(new CommentUpdateController.Response<>(200,
                        "댓글이 수정되었습니다.", details(7, "원본", "작성자"))), JsonCompareMode.STRICT));
        verify(comments).findById(12);
        verify(users).findNickname(7);
        verifyNoInteractions(modification);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\" \"}", "{\"content\":\" \\t\\r\\n \"}"})
    void given__missing_null_or_ascii_blank_content__when__updating_comment__then__validate_before_comment_ownership_or_nickname_lookup(String body) throws Exception {
        mvc.perform(authenticatedPut("12").content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"content: 내용은 필수입니다\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(comments, users, modification, createComment, deletion, listComments, readPost, listPosts);
    }

    @Test
    void given__same_blank_stored_content__when__updating_with_blank_content__then__mvc_validation_is_not_bypassed_as_noop() throws Exception {
        when(comments.findById(12)).thenReturn(Optional.of(snapshot(7, " ")));

        mvc.perform(authenticatedPut("12").content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("content: 내용은 필수입니다"));
        verifyNoInteractions(comments, users, modification);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "[]"})
    void given__missing_or_malformed_json_body__when__updating_comment__then__safe_400_precedes_business_calls(String body) throws Exception {
        mvc.perform(authenticatedPut("12").content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"요청 본문 형식이 올바르지 않습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(comments, users, modification, createComment, deletion, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_untrusted_token_and_bad_request__when__updating_comment__then__authenticate_before_id_and_json_binding(String authorization, String message) throws Exception {
        var request = put("/api/v1/comments/not-a-number").contentType(MediaType.APPLICATION_JSON).content("{")
                .header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().json(mapper.writeValueAsString(new CommentUpdateController.Response<>(401, message, null)), JsonCompareMode.STRICT));
        verifyNoInteractions(comments, users, modification, createComment, deletion, listComments, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> rejectedAuthorizations() {
        String expired = Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        var otherKey = Keys.hmacShaKeyFor("f11-synthetic-other-signing-key-only-012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
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
    void given__signed_invalid_identity__when__updating_comment__then__reject_before_binding_or_business_calls(Map<String, Object> claims) throws Exception {
        String token = Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(F3TestInputs.KEY).compact();

        mvc.perform(put("/api/v1/comments/not-a-number").contentType(MediaType.APPLICATION_JSON).content("{")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":401,\"message\":\"토큰 파싱에 실패했습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(comments, users, modification, createComment, deletion, listComments, readPost, listPosts);
    }

    @Test
    void given__two_owners_then_anonymous_request__when__updating_comments__then__clear_previous_identity() throws Exception {
        when(comments.findById(12)).thenReturn(Optional.of(snapshot(7, "원본")), Optional.of(snapshot(8, "원본")));
        when(users.findNickname(8)).thenReturn(Optional.of("다른 작성자"));
        for (long userId : new long[] {7, 8}) {
            mvc.perform(put("/api/v1/comments/12").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"원본\"}")
                            .header("Authorization", "Bearer " + F3TestInputs.token(userId, "ROLE_USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.authorId").value((int) userId));
        }
        mvc.perform(put("/api/v1/comments/12").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"원본\"}"))
                .andExpect(status().isUnauthorized());
        verify(comments, times(2)).findById(12);
        verify(users).findNickname(7);
        verify(users).findNickname(8);
        verifyNoMoreInteractions(users, comments);
        verifyNoInteractions(modification);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {12, 0, -1})
    void given__no_active_comment__when__updating__then__return_404_before_nickname_and_writes_without_new_id_validation(long id) throws Exception {
        when(comments.findById(id)).thenReturn(Optional.empty());

        mvc.perform(authenticatedPut(Long.toString(id)).content("{\"content\":\"수정\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"댓글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(comments).findById(id);
        verifyNoInteractions(users, modification);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__another_authors_comment_for_any_role__when__updating__then__403_precedes_nickname_and_write_without_admin_override(String role) throws Exception {
        when(comments.findById(12)).thenReturn(Optional.of(snapshot(8, "원본")));

        mvc.perform(put("/api/v1/comments/12").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"수정\"}")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":403,\"message\":\"댓글에 접근할 권한이 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verify(comments).findById(12);
        verifyNoInteractions(users, modification);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9223372036854775808"})
    void given__unparseable_comment_id_with_valid_identity__when__updating__then__preserve_legacy_safe_500(String id) throws Exception {
        mvc.perform(authenticatedPut(id).content("{\"content\":\"수정\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(comments, users, modification);
    }

    @ParameterizedTest
    @MethodSource("updateFailures")
    void given__lookup_nickname_or_write_failure__when__updating__then__preserve_safe_error_contract_and_omit_sensitive_logs(int stage, int expectedStatus, String message) throws Exception {
        var cause = new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password");
        var failure = new IllegalStateException("Bearer synthetic-private-token; synthetic-private-comment", cause);
        failure.addSuppressed(new RuntimeException("https://fixture.invalid/synthetic-private-url"));
        switch (stage) {
            case 0 -> when(comments.findById(12)).thenThrow(failure);
            case 1 -> when(users.findNickname(7)).thenThrow(failure);
            case 2 -> doThrow(new UnsupportedCommentCharactersException(failure)).when(modification).update(12, "synthetic-private-comment");
            case 3 -> doThrow(failure).when(modification).update(12, "synthetic-private-comment");
            default -> throw new AssertionError("Unknown synthetic failure stage");
        }
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(authenticatedPut("12").content("{\"content\":\"synthetic-private-comment\"}"))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(content().json(mapper.writeValueAsString(new CommentUpdateController.Response<>(expectedStatus, message, null)), JsonCompareMode.STRICT));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-private-token", "synthetic-private-comment",
                        "synthetic-private-password", "synthetic-private-url", "Bearer ", "jdbc:postgresql:");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        verify(comments).findById(12);
        if (stage == 0) verifyNoInteractions(users, modification);
        else {
            verify(users).findNickname(7);
            if (stage == 1) verifyNoInteractions(modification);
            else verify(modification).update(12, "synthetic-private-comment");
        }
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Stream<Arguments> updateFailures() {
        return Stream.of(Arguments.of(0, 500, "서버 에러!"), Arguments.of(1, 500, "서버 에러!"),
                Arguments.of(2, 400, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."), Arguments.of(3, 500, "서버 에러!"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unavailable", "empty", "space"})
    void given__legacy_nickname_result__when__updating_same_content__then__serialize_fallback_null_or_whitespace_without_extra_fields(String kind) throws Exception {
        String nickname;
        switch (kind) {
            case "missing" -> { when(users.findNickname(7)).thenReturn(Optional.empty()); nickname = "사용자7"; }
            case "unavailable" -> { when(users.findNickname(7)).thenThrow(new UserNicknameUnavailableException(new IllegalStateException("synthetic unavailable"))); nickname = "사용자7"; }
            case "empty" -> { when(users.findNickname(7)).thenReturn(Optional.of("")); nickname = null; }
            case "space" -> { when(users.findNickname(7)).thenReturn(Optional.of(" ")); nickname = " "; }
            default -> throw new AssertionError("Unknown synthetic nickname result");
        }

        mvc.perform(authenticatedPut("12").content("{\"content\":\"원본\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(content().json(mapper.writeValueAsString(new CommentUpdateController.Response<>(200,
                        "댓글이 수정되었습니다.", details(7, "원본", nickname))), JsonCompareMode.STRICT));
        verifyNoInteractions(modification);
    }

    @Test
    void given__existing_comment_read_create_delete_and_post_404__when__requesting__then__put_advice_and_authentication_remain_scoped() throws Exception {
        var comment = details(7, "댓글", "작성자");
        when(listComments.read(42)).thenReturn(List.of(comment));
        when(createComment.create(42, 7, "댓글")).thenReturn(comment);
        when(readPost.read(42)).thenThrow(new PostNotFoundException());

        mvc.perform(get("/api/v1/comments/posts/read/42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].commentId").value(12));
        mvc.perform(post("/api/v1/comments/posts/42").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"댓글\"}")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("댓글이 생성되었습니다."));
        mvc.perform(delete("/api/v1/comments/12").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"댓글이 삭제되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/posts/read/42")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(comments, users, modification, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/comments/12", "POST /api/v1/comments/12", "PATCH /api/v1/comments/12",
            "HEAD /api/v1/comments/12", "OPTIONS /api/v1/comments/12", "PUT /api/v1/comments", "PUT /api/v1/comments/",
            "PUT /api/v1/comments/12/", "PUT /api/v1/comments/12/extra", "PUT /api/v1/comments/posts/42",
            "PUT /api/v1/comments/posts/read/42", "PUT /api/comments/12", "PUT /api/v1/admin/comments/12"})
    void given__unexposed_method_or_adjacent_path__when__requesting_with_valid_token__then__deny_without_business_calls(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(comments, users, modification, createComment, deletion, listComments, readPost, listPosts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /api/v1/posts", "PUT /api/v1/posts/42", "DELETE /api/v1/posts/42",
            "POST /api/v1/comments/posts/42", "DELETE /api/v1/comments/12"})
    void given__existing_write_without_token__when__requesting__then__preserve_jwt_authentication(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(comments, users, modification, createComment, deletion, listComments, readPost, listPosts);
    }

    private static MockHttpServletRequestBuilder authenticatedPut(String id) {
        return put("/api/v1/comments/" + id).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }

    private static CommentSnapshot snapshot(long author, String content) {
        return new CommentSnapshot(12, 42, author, content, CREATED, UPDATED);
    }

    private static CommentDetails details(long author, String content, String nickname) {
        return new CommentDetails(12, 42, author, nickname, content, CREATED, UPDATED);
    }
}
