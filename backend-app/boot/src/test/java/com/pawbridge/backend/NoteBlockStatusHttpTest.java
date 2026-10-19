package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.sql.SQLException;
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

@WebMvcTest(controllers = NoteBlockStatusController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteBlockStatusController.class, NoteBlockStatusExceptionHandler.class, ReadBlockedAuthors.class,
        NoteRecipientController.class, NoteRecipientExceptionHandler.class, ReadNoteRecipient.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteBlockStatusHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean NoteBlockStatusReader blocks;
    @MockitoBean NoteBlockReader recipientBlocks;

    @Test
    void given__verified_principal_and_spoofed_identity__when__reading_block_status__then__return_only_reader_ordered_raw_ids_and_query_only_current_member() throws Exception {
        long largeId = Long.MAX_VALUE;
        var distinctIds = List.of(largeId, 9L, 7L, 8L);
        when(members.get(7)).thenReturn(active(7));
        when(blocks.blockedAuthors(7, distinctIds)).thenReturn(List.of(8L, 9L, largeId));

        var response = mvc.perform(authenticatedStatus().param("memberIds", largeId + ",9,7,8,9")
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999,\"memberIds\":[999]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"memberIds\":[8,9,"
                        + largeId + "]}}", JsonCompareMode.STRICT))
                .andReturn().getResponse();

        var rawId = mapper.readTree(response.getContentAsString()).path("data").path("memberIds").get(2);
        assertThat(rawId.isIntegralNumber()).isTrue();
        assertThat(rawId.longValue()).isEqualTo(largeId);
        var order = inOrder(members, blocks);
        order.verify(members).get(7);
        order.verify(blocks).blockedAuthors(7, distinctIds);
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(recipientBlocks);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__no_matching_outgoing_blocks__when__reading__then__return_an_empty_array() throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(blocks.blockedAuthors(7, List.of(8L))).thenReturn(List.of());

        mvc.perform(authenticatedStatus().param("memberIds", "8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"memberIds\":[]}}", JsonCompareMode.STRICT));

        verify(members).get(7);
        verify(blocks).blockedAuthors(7, List.of(8L));
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(recipientBlocks);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_jwt_with_unconvertible_ids__when__reading__then__authenticate_before_binding_or_dependencies(String authorization, String message) throws Exception {
        var request = get("/api/v1/notes/blocks/status").param("memberIds", "abc").header("X-User-Id", "7");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, blocks, recipientBlocks);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @Test
    void given__authenticated_request_without_query_ids__when__binding__then__return_common_400_envelope_before_dependencies() throws Exception {
        mvc.perform(authenticatedStatus().contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[8]}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":400,\"message\":\"필수 요청 파라미터 'memberIds'(이)가 누락되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, blocks, recipientBlocks);
    }

    @Test
    void given__authenticated_csv_mixed_with_repeated_parameter__when__binding__then__return_message_only_conversion_400_before_dependencies() throws Exception {
        mvc.perform(authenticatedStatus().param("memberIds", "8,9", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, blocks, recipientBlocks);
    }

    @Test
    void given__authenticated_empty_query_value__when__validating_bound_list__then__return_message_only_list_400_before_member_lookup() throws Exception {
        mvc.perform(authenticatedStatus().param("memberIds", ""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, blocks, recipientBlocks);
    }

    @Test
    void given__pending_current_member__when__reading_valid_ids__then__return_message_only_403_without_block_query() throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));

        mvc.perform(authenticatedStatus().param("memberIds", "8"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT));

        verify(members).get(7);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(blocks, recipientBlocks);
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    void given__user_or_block_dependency_failure__when__reading_valid_ids__then__preserve_source_specific_message_only_503(String source, RuntimeException failure, String message) throws Exception {
        if (source.equals("user")) when(members.get(7)).thenThrow(failure);
        else {
            when(members.get(7)).thenReturn(active(7));
            when(blocks.blockedAuthors(7, List.of(8L))).thenThrow(failure);
        }

        mvc.perform(authenticatedStatus().param("memberIds", "8"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));

        verify(members).get(7);
        if (source.equals("user")) verifyNoInteractions(blocks);
        else verify(blocks).blockedAuthors(7, List.of(8L));
        verifyNoMoreInteractions(members, blocks);
        verifyNoInteractions(recipientBlocks);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> dependencyFailures() {
        return Stream.of(
                Arguments.of("user", new ContactMemberUnavailableException(new DataAccessResourceFailureException("synthetic-private-user-query")),
                        "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("block", new DataAccessResourceFailureException("synthetic-private-block-query", new SQLException("synthetic-private-password")),
                        "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("block", new IllegalStateException("Bearer synthetic-private-token"),
                        "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("BLOCK_BEGIN", new CannotCreateTransactionException("synthetic-private-begin"),
                        "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("BLOCK_COMMIT", new TransactionSystemException("synthetic-private-commit"),
                        "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("BLOCK_TIMEOUT", new TransactionTimedOutException("synthetic-private-timeout"),
                        "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("USAGE", new IllegalTransactionStateException("synthetic-private-usage"),
                        "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/notes/notifications", "GET /api/v1/notes/blocks/status/", "GET /api/notes/blocks/status",
            "HEAD /api/v1/notes/blocks/status", "OPTIONS /api/v1/notes/blocks/status", "POST /api/v1/notes/blocks/status",
            "PUT /api/v1/notes/blocks/status", "DELETE /api/v1/notes/blocks/status"})
    void given__unexposed_neighbor_path_or_method__when__requesting_with_a_valid_token__then__deny_before_dependencies(String request) throws Exception {
        String[] parts = request.split(" ", 2);

        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(members, blocks, recipientBlocks);
    }

    @Test
    void given__existing_recipient_route__when__its_own_rejection_occurs__then__retain_recipient_advice() throws Exception {
        when(members.get(7)).thenReturn(active(7));

        mvc.perform(get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT));

        verify(members).get(7);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(blocks, recipientBlocks);
    }

    private MockHttpServletRequestBuilder authenticatedStatus() {
        return get("/api/v1/notes/blocks/status").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }

    private static ContactMember active(long id) { return new ContactMember(id, "합성 회원", true, false); }
}
