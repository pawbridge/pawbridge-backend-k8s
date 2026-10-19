package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

@WebMvcTest(controllers = NoteBlockListController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteBlockListController.class, NoteBlockListExceptionHandler.class, ListNoteBlocks.class,
        NoteRecipientController.class, NoteRecipientExceptionHandler.class, ReadNoteRecipient.class,
        NoteBlockStatusController.class, NoteBlockStatusExceptionHandler.class, ReadBlockedAuthors.class,
        TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteBlockListHttpTest {
    private static final String PATH = "/api/v1/notes/blocks";
    private static final Instant CREATED_AT = Instant.parse("2026-10-19T15:00:00.123456Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean ContactMembersQuery batches;
    @MockitoBean NoteBlockListReader blocks;
    @MockitoBean NoteBlockReader recipientBlocks;
    @MockitoBean NoteBlockStatusReader statusBlocks;
    @MockitoBean TotalUserCountQuery counts;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__ordinary_authenticated_role_and_spoofed_identity__when__reading_default_page__then__return_exact_page_and_raw_ids_instants_after_one_batch(String role) throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(blocks.count(7)).thenReturn(11L);
        when(blocks.read(7, 0)).thenReturn(List.of(new NoteBlockListReader.Block(Long.MAX_VALUE, CREATED_AT),
                new NoteBlockListReader.Block(8, CREATED_AT.minusNanos(1000))));
        when(batches.getAll(List.of(Long.MAX_VALUE, 8L))).thenReturn(List.of(
                new ContactMember(8L, "원래 닉네임을 노출하지 않음", false, true),
                new ContactMember(Long.MAX_VALUE, "  ", true, false)));

        var response = mvc.perform(authenticatedList(role)
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999").param("role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999,\"page\":9}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{"
                        + "\"content\":[{\"memberId\":" + Long.MAX_VALUE + ",\"nickname\":\"  \",\"createdAt\":\"2026-10-19T15:00:00.123456Z\"},"
                        + "{\"memberId\":8,\"nickname\":\"탈퇴한 회원\",\"createdAt\":\"2026-10-19T15:00:00.123455Z\"}],"
                        + "\"totalElements\":11,\"totalPages\":2,\"number\":0}}", JsonCompareMode.STRICT))
                .andReturn().getResponse();

        var rawId = mapper.readTree(response.getContentAsString()).path("data").path("content").get(0).path("memberId");
        assertThat(rawId.isIntegralNumber()).isTrue();
        assertThat(rawId.longValue()).isEqualTo(Long.MAX_VALUE);
        var order = inOrder(members, blocks, batches);
        order.verify(members).get(7);
        order.verify(blocks).count(7);
        order.verify(blocks).read(7, 0);
        order.verify(batches).getAll(List.of(Long.MAX_VALUE, 8L));
        verifyNoMoreInteractions(members, blocks, batches);
        verifyNoInteractions(recipientBlocks, statusBlocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__empty_page_at_maximum_page_number__when__reading__then__retain_total_and_requested_page_without_batch_lookup() throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(blocks.count(7)).thenReturn(11L);
        when(blocks.read(7, 10000)).thenReturn(List.of());

        mvc.perform(authenticatedList("ROLE_USER").param("page", "10000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{"
                        + "\"content\":[],\"totalElements\":11,\"totalPages\":2,\"number\":10000}}", JsonCompareMode.STRICT));

        var order = inOrder(members, blocks);
        order.verify(members).get(7);
        order.verify(blocks).count(7);
        order.verify(blocks).read(7, 10000);
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(batches, recipientBlocks, statusBlocks, counts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_jwt_with_malformed_page__when__reading__then__authenticate_before_binding_and_all_dependencies(String authorization, String message) throws Exception {
        var request = get(PATH).param("page", "abc").header("X-User-Id", "7");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(
                        new NoteBlockListController.Response<>(401, message, null)), JsonCompareMode.STRICT));

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648"})
    void given__authenticated_unconvertible_page__when__binding__then__return_message_only_400_before_member_lookup(String page) throws Exception {
        mvc.perform(authenticatedList("ROLE_USER").param("page", page))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "10001"})
    void given__authenticated_out_of_range_page__when__validating__then__return_page_400_before_any_member_dependency(String page) throws Exception {
        mvc.perform(authenticatedList("ROLE_USER").param("page", page))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts);
    }

    @Test
    void given__pending_current_member__when__reading_a_valid_page__then__return_inactive_403_before_count_page_or_batch() throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));

        mvc.perform(authenticatedList("ROLE_USER").param("page", "0"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT));

        verify(members).get(7);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(batches, blocks, recipientBlocks, statusBlocks, counts);
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    void given__caller_batch_or_block_failure__when__reading_a_valid_page__then__preserve_phase_specific_message_only_503_and_stop(
            String phase, RuntimeException failure, String message) throws Exception {
        if (phase.equals("CURRENT")) when(members.get(7)).thenThrow(failure);
        else {
            when(members.get(7)).thenReturn(active(7));
            if (phase.equals("COUNT_DAO")) when(blocks.count(7)).thenThrow(failure);
            else {
                when(blocks.count(7)).thenReturn(1L);
                if (phase.equals("BATCH")) {
                    when(blocks.read(7, 0)).thenReturn(List.of(new NoteBlockListReader.Block(8, CREATED_AT)));
                    when(batches.getAll(List.of(8L))).thenThrow(failure);
                } else when(blocks.read(7, 0)).thenThrow(failure);
            }
        }

        mvc.perform(authenticatedList("ROLE_USER").param("page", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));

        verify(members).get(7);
        if (phase.equals("CURRENT")) verifyNoInteractions(blocks, batches);
        else {
            verify(blocks).count(7);
            if (!phase.equals("COUNT_DAO")) verify(blocks).read(7, 0);
            if (phase.equals("BATCH")) verify(batches).getAll(List.of(8L));
            else verifyNoInteractions(batches);
        }
        verifyNoMoreInteractions(members, blocks, batches);
        verifyNoInteractions(recipientBlocks, statusBlocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> dependencyFailures() {
        String member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        return Stream.of(
                Arguments.of("CURRENT", new ContactMemberUnavailableException(new DataAccessResourceFailureException("synthetic-private-caller")), member),
                Arguments.of("BATCH", new ContactMemberUnavailableException(new TransactionSystemException("synthetic-private-batch-commit")), member),
                Arguments.of("COUNT_DAO", new DataAccessResourceFailureException("synthetic-private-count"), storage),
                Arguments.of("LIST_DAO", new DataAccessResourceFailureException("synthetic-private-list", new SQLException("synthetic-private-password")), storage),
                Arguments.of("BLOCK_BEGIN", new CannotCreateTransactionException("synthetic-private-begin"), storage),
                Arguments.of("BLOCK_COMMIT", new TransactionSystemException("synthetic-private-commit"), storage),
                Arguments.of("BLOCK_TIMEOUT", new TransactionTimedOutException("synthetic-private-timeout"), storage),
                Arguments.of("USAGE", new IllegalTransactionStateException("synthetic-private-usage"), generic),
                Arguments.of("RUNTIME", new IllegalStateException("Bearer synthetic-private-token"), generic));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/notes/stream", "GET /api/v1/notes/blocks/", "GET /api/notes/blocks",
            "HEAD /api/v1/notes/blocks", "OPTIONS /api/v1/notes/blocks", "POST /api/v1/notes/blocks",
            "PUT /api/v1/notes/blocks", "DELETE /api/v1/notes/blocks"})
    void given__unexposed_neighbor_path_or_method__when__requesting_with_a_valid_token__then__deny_before_all_dependencies(String request) throws Exception {
        String[] parts = request.split(" ", 2);

        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(members, batches, blocks, recipientBlocks, statusBlocks, counts);
    }

    @Test
    void given__existing_recipient_status_and_admin_routes__when__their_own_rejections_occur__then__retain_scoped_policies_without_block_list_queries() throws Exception {
        when(members.get(7)).thenReturn(active(7));

        mvc.perform(get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/notes/blocks/status").param("memberIds", "0")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/admin/users/stats/total-users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));

        verify(members).get(7);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(batches, blocks, recipientBlocks, statusBlocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequestBuilder authenticatedList(String role) {
        return get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, role));
    }

    private static ContactMember active(long id) { return new ContactMember(id, "본인", true, false); }
}
