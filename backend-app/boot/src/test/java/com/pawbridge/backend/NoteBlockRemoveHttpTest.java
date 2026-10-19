package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval;
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock;
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
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

@WebMvcTest(controllers = NoteBlockRemoveController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteBlockRemoveController.class, NoteBlockRemoveExceptionHandler.class, RemoveNoteBlock.class,
        NoteBlockStatusController.class, NoteBlockStatusExceptionHandler.class, ReadBlockedAuthors.class,
        TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteBlockRemoveHttpTest {
    private static final String PATH = "/api/v1/notes/blocks/";
    private static final String SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final String MEMBER = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    private static final String STORAGE = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    private static final String GENERIC = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean NoteBlockRemoval removal;
    @MockitoBean NoteBlockStatusReader statuses;
    @MockitoBean TotalUserCountQuery counts;

    @ParameterizedTest
    @ValueSource(longs = {7, Long.MAX_VALUE})
    void given__self_or_full_long_target_and_spoofed_identity__when__removing__then__use_signed_caller_and_return_empty_200_after_current_callback(long target) throws Exception {
        when(members.get(7)).thenReturn(active());
        // The mock models the port's callback position, not database locks, row counts or a transaction.
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(removal).remove(eq(7L), eq(target), any(Runnable.class));

        mvc.perform(authenticated(PATH + target).header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"));

        var order = inOrder(removal, members);
        order.verify(removal).remove(eq(7L), eq(target), any(Runnable.class));
        order.verify(members).get(7);
        verifyNoMoreInteractions(removal, members);
        verifyNoInteractions(statuses, counts);
        contextCleared();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INVALID"})
    void given__missing_or_bad_jwt_and_malformed_target__when__removing__then__authenticate_before_binding_or_business_work(String token) throws Exception {
        var request = delete(PATH + "not-a-long").header("X-User-Id", "7");
        if (token.equals("INVALID")) request.header("Authorization", "Bearer invalid");
        String message = token.equals("MISSING") ? "인증 토큰이 필요합니다." : "유효하지 않거나 만료된 토큰입니다.";
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void given__nonpositive_target_with_valid_jwt__when__removing__then__keep_business_login_401_before_removal_or_member_lookup(long target) throws Exception {
        mvc.perform(authenticated(PATH + target)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-long", "9223372036854775808", "status"})
    void given__malformed_or_overflow_target_with_valid_jwt__when__removing__then__return_message_only_400_before_business_work(String target) throws Exception {
        mvc.perform(authenticated(PATH + target)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @Test
    void given__pending_current_member__when__removal_invokes_its_guard__then__return_message_only_403() throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(removal).remove(eq(7L), eq(8L), any(Runnable.class));

        mvc.perform(authenticated(PATH + "8")).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT));
        verify(removal).remove(eq(7L), eq(8L), any(Runnable.class));
        verify(members).get(7); verifyNoMoreInteractions(removal, members);
        verifyNoInteractions(statuses, counts);
        contextCleared();
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    void given__member_dao_or_writer_runtime_failure__when__removing__then__keep_source_specific_503_and_clear_security_context(String phase, RuntimeException failure, String message) throws Exception {
        boolean beforeGuard = List.of("BEGIN", "TIMEOUT", "USAGE").contains(phase);
        if (phase.equals("USER")) when(members.get(7)).thenThrow(failure);
        else if (!beforeGuard) when(members.get(7)).thenReturn(active());
        doAnswer(call -> {
            if (beforeGuard) throw failure;
            ((Runnable) call.getArgument(2)).run();
            throw failure;
        }).when(removal).remove(eq(7L), eq(8L), any(Runnable.class));

        mvc.perform(authenticated(PATH + "8")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));
        verify(removal).remove(eq(7L), eq(8L), any(Runnable.class));
        if (beforeGuard) verifyNoInteractions(members); else verify(members).get(7);
        verifyNoMoreInteractions(removal, members);
        verifyNoInteractions(statuses, counts);
        contextCleared();
    }

    static Stream<Arguments> dependencyFailures() {
        return Stream.of(
                Arguments.of("USER", new ContactMemberUnavailableException(new TransactionSystemException("synthetic-private-user-commit")), MEMBER),
                Arguments.of("DAO", new DataAccessResourceFailureException("synthetic-private-delete"), STORAGE),
                Arguments.of("BEGIN", new CannotCreateTransactionException("synthetic-private-begin"), GENERIC),
                Arguments.of("COMMIT", new TransactionSystemException("synthetic-private-commit"), GENERIC),
                Arguments.of("TIMEOUT", new TransactionTimedOutException("synthetic-private-timeout"), GENERIC),
                Arguments.of("USAGE", new IllegalTransactionStateException("synthetic-private-usage"), GENERIC),
                Arguments.of("RUNTIME", new IllegalStateException("synthetic-private-runtime"), GENERIC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INVALID", "VALID"})
    void given__stream_with_missing_bad_or_valid_jwt__when__requesting__then__retain_default_403_without_removal_or_sse_work(String token) throws Exception {
        var request = get("/api/v1/notes/stream").accept(MediaType.TEXT_EVENT_STREAM);
        if (!token.equals("MISSING")) request.header("Authorization", token.equals("INVALID") ? "Bearer invalid" : "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
        mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT /api/v1/notes/blocks/8", "PUT /api/v1/notes/00000000-0000-0000-0000-000000000008/read",
            "DELETE /api/v1/notes/00000000-0000-0000-0000-000000000008", "DELETE /api/v1/notes/blocks/8/extra"})
    void given__unexposed_block_note_mutation_or_deeper_path__when__requesting__then__retain_default_403_without_removal_work(String wire) throws Exception {
        String[] parts = wire.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @Test
    void given__existing_read_only_tx_failure__when__querying_block_status__then__keep_its_storage_policy_outside_remove_advice() throws Exception {
        when(members.get(7)).thenReturn(active());
        when(statuses.blockedAuthors(7, List.of(8L))).thenThrow(new TransactionSystemException("synthetic-status-commit"));
        mvc.perform(get("/api/v1/notes/blocks/status").param("memberIds", "8")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", STORAGE)), JsonCompareMode.STRICT));
        verify(members).get(7); verify(statuses).blockedAuthors(7, List.of(8L));
        verifyNoMoreInteractions(members, statuses); verifyNoInteractions(removal, counts);
        contextCleared();
    }

    @Test
    void given__existing_admin_permission_and_runtime_error__when__counting_users__then__keep_admin_403_and_common_500_outside_remove_advice() throws Exception {
        when(counts.count()).thenThrow(new IllegalStateException("synthetic-admin-count"));
        mvc.perform(get("/api/v1/admin/users/stats/total-users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(counts);
        mvc.perform(get("/api/v1/admin/users/stats/total-users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verify(counts).count(); verifyNoMoreInteractions(counts);
        verifyNoInteractions(removal, members, statuses);
        contextCleared();
    }

    private MockHttpServletRequestBuilder authenticated(String path) {
        return delete(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }

    private void noDependencies() {
        verifyNoInteractions(removal, members, statuses, counts);
        contextCleared();
    }

    private static void contextCleared() { assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); }
    private static ContactMember active() { return new ContactMember(7L, "회원", true, false); }
}
