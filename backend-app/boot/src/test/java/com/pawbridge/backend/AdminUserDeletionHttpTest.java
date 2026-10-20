package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException;
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
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

@WebMvcTest(controllers=AdminUserDeletionController.class,excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminUserDeletionController.class,AdminUserDeletionExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AdminUserDeletionHttpTest {
    private static final String PATH="/api/v1/admin/users/";
    private static final String CACHE="no-cache, no-store, max-age=0, must-revalidate";
    @Autowired MockMvc mvc;
    @MockitoBean UserDeletion deletion;

    @Test
    void given__verified_admin_and_spoofed_member_headers__when__deleting__then__use_only_the_bound_target_and_exact_original_envelope() throws Exception {
        mvc.perform(delete(PATH+Long.MAX_VALUE).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN"))
                        .header("X-User-Id","8").header("X-User-Role","ROLE_USER").param("userId","8")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":8}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"회원이 삭제되었습니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verify(deletion).delete(Long.MAX_VALUE);verifyNoMoreInteractions(deletion);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__signed_nonadmin_with_spoofed_admin_header__when__deleting_an_invalid_target__then__authorize_before_binding_or_business() throws Exception {
        mvc.perform(delete(PATH+"not-a-number").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"))
                        .header("X-User-Id","1").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(deletion);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest @MethodSource("rejectedTokens")
    void given__missing_or_invalid_bearer__when__deleting_a_malformed_target__then__reject_before_binding_and_clear_context(String authorization,String message) throws Exception {
        var request=delete(PATH+"not-a-number").header("X-User-Role","ROLE_ADMIN");
        if(authorization!=null) request.header("Authorization",authorization);
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":401,\"message\":\""+message+"\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(deletion);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    static Stream<Arguments> rejectedTokens(){return Stream.of(Arguments.of(null,"인증 토큰이 필요합니다."),Arguments.of("Bearer invalid","유효하지 않거나 만료된 토큰입니다."));}

    @ParameterizedTest @MethodSource("failures")
    void given__authorized_deletion_failure__when__the_scoped_advice_handles_it__then__preserve_user_status_without_private_details(RuntimeException failure,int status,String message) throws Exception {
        doThrow(failure).when(deletion).delete(8);
        mvc.perform(delete(PATH+8).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().is(status)).andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":"+status+",\"message\":\""+message+"\",\"data\":null}",JsonCompareMode.STRICT));
        verify(deletion).delete(8);verifyNoMoreInteractions(deletion);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    static Stream<Arguments> failures(){return Stream.of(Arguments.of(new UserNotFoundException(),400,"존재하지 않는 회원입니다."),
            Arguments.of(new MemberCleanupUnavailableException(),503,"쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요."),
            Arguments.of(new DataAccessResourceFailureException("synthetic-private-query",new SQLException("synthetic-private-password")),500,"서버 에러!"));}

    @ParameterizedTest @ValueSource(strings={"GET /api/v1/admin/users/8/extra","PUT /api/v1/admin/users/8/extra","DELETE /api/v1/admin/users/8/extra","DELETE /internal/private-notes/members/8"})
    void given__an_unexposed_neighbor_or_internal_route__when__a_valid_admin_requests_it__then__keep_generic_default_deny(String wire) throws Exception {
        var parts=wire.split(" ",2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]),parts[1]).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(deletion);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__an_admin_and_malformed_long__when__binding_before_deletion__then__use_the_existing_scoped_safe_runtime_envelope() throws Exception {
        mvc.perform(delete(PATH+"not-a-number").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(deletion);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
