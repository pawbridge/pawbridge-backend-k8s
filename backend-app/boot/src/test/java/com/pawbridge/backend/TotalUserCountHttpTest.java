package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.PostReadController;
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = TotalUserCountController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        NoteRecipientController.class, NoteRecipientExceptionHandler.class, ReadNoteRecipient.class,
        PostReadController.class, PostReadExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class TotalUserCountHttpTest {
    private static final String PATH = "/api/v1/admin/users/stats/total-users";
    private static final String SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean TotalUserCountQuery counts;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean NoteBlockReader blocks;
    @MockitoBean ReadPost readPost;
    @MockitoBean ListPosts listPosts;

    @ParameterizedTest
    @ValueSource(longs = {0, Long.MAX_VALUE})
    void given__verified_admin_without_member_state_lookup__when__counting__then__return_the_exact_envelope_and_raw_long(long count) throws Exception {
        when(counts.count()).thenReturn(count);

        var response = mvc.perform(countWithRole("ROLE_ADMIN")
                        .header("X-User-Id", "0").header("X-User-Role", "ROLE_USER")
                        .param("userId", "0").param("role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":0,\"role\":\"ROLE_USER\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":" + count + "}",
                        JsonCompareMode.STRICT))
                .andReturn().getResponse();

        var rawCount = mapper.readTree(response.getContentAsString()).path("data");
        assertThat(rawCount.isIntegralNumber()).isTrue();
        assertThat(rawCount.longValue()).isEqualTo(count);
        verify(counts).count();
        verifyNoMoreInteractions(counts);
        verifyNoInteractions(members, blocks, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_SHELTER", "", " ", "ROLE_UNKNOWN", "ROLE_ADMIN "})
    void given__nonadmin_signed_role_and_spoofed_admin_identity__when__counting__then__return_only_this_routes_admin_403_before_queries(String role) throws Exception {
        mvc.perform(countWithRole(role).header("X-User-Id", "1").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "1").param("role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1,\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));

        verifyNoInteractions(counts, members, blocks, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_bearer_with_spoofed_admin_header__when__counting__then__return_original_401_before_queries(String authorization, String message) throws Exception {
        var request = get(PATH).header("X-User-Id", "1").header("X-User-Role", "ROLE_ADMIN");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(
                        new TotalUserCountController.Response<>(401, message, null)), JsonCompareMode.STRICT));

        verifyNoInteractions(counts, members, blocks, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @ParameterizedTest
    @MethodSource("countFailures")
    void given__count_dependency_failure_after_admin_authorization__when__counting__then__return_common_safe_500_without_reclassifying_authentication(RuntimeException failure) throws Exception {
        when(counts.count()).thenThrow(failure);

        mvc.perform(countWithRole("ROLE_ADMIN"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));

        verify(counts).count();
        verifyNoMoreInteractions(counts);
        verifyNoInteractions(members, blocks, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> countFailures() {
        return Stream.of(Arguments.of(new DataAccessResourceFailureException("synthetic-private-query", new SQLException("synthetic-private-password"))),
                Arguments.of(new IllegalStateException("Bearer synthetic-private-token")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD /api/v1/admin/users/stats/total-users", "OPTIONS /api/v1/admin/users/stats/total-users",
            "POST /api/v1/admin/users/stats/total-users", "PUT /api/v1/admin/users/stats/total-users",
            "DELETE /api/v1/admin/users/stats/total-users", "GET /api/v1/admin/users/stats/total-users/",
            "GET /api/admin/users/stats/total-users", "GET /api/v1/admin/users/extra/extra", "GET /api/v1/admin/users/stats/active-users",
            "DELETE /api/v1/admin/users/8/extra"})
    void given__unexposed_admin_neighbor_or_method__when__requesting_with_a_valid_admin_token__then__retain_generic_403_before_queries(String request) throws Exception {
        String[] parts = request.split(" ", 2);

        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));

        verifyNoInteractions(counts, members, blocks, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "", " ", "ROLE_UNKNOWN"})
    void given__admin_blank_or_unknown_role_on_existing_notes__when__reading_recipient__then__retain_long_principal_and_existing_authenticated_policy(String role) throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "본인", true, false));
        when(members.get(8)).thenReturn(new ContactMember(8L, "상대", true, false));

        mvc.perform(get("/api/v1/notes/recipients/8").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        var order = inOrder(members, blocks);
        order.verify(members).get(7);
        order.verify(blocks).blocked(7, 8);
        order.verify(members).get(8);
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(counts, readPost, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__public_post_read_with_bad_bearer__when__its_own_failure_occurs__then__retain_public_access_and_its_scoped_500() throws Exception {
        when(readPost.read(42)).thenThrow(new IllegalStateException("synthetic-private-post"));

        mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));

        verify(readPost).read(42);
        verifyNoMoreInteractions(readPost);
        verifyNoInteractions(counts, members, blocks, listPosts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequestBuilder countWithRole(String role) {
        return get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(Long.MAX_VALUE, role));
    }
}
