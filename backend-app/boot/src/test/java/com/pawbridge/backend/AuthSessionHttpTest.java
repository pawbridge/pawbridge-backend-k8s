package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.session.application.*;
import com.pawbridge.backend.user.internal.session.web.*;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(controllers=AuthSessionController.class,properties="pawbridge.auth.sessions-enabled=true",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ContextConfiguration(classes={AuthSessionController.class,AuthSessionExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthSessionHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthSessions sessions;
    @Test void given__refresh_body_with_invalid_access_header__when__refreshing__then__use_only_body_and_exact_envelope() throws Exception {
        when(sessions.refresh("synthetic-input")).thenReturn(new AuthSessions.Tokens("synthetic-access","synthetic-refresh"));
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"synthetic-input\"}")
                        .header("Authorization","Bearer invalid").header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"토큰이 재발급되었습니다.\",\"data\":{\"accessToken\":\"synthetic-access\",\"refreshToken\":\"synthetic-refresh\"}}",JsonCompareMode.STRICT));
        verify(sessions).refresh("synthetic-input");verifyNoMoreInteractions(sessions);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"refreshToken\":null}","{\"refreshToken\":\" \"}"})
    void given__missing_or_blank_refresh__when__posting__then__original_validation400_before_service(String body) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"refreshToken: 리프레시 토큰은 필수입니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(sessions);
    }
    @ParameterizedTest @MethodSource("failures")
    void given__original_refresh_business_rejection__when__posting__then__preserve_status_and_message(RuntimeException failure) throws Exception {
        when(sessions.refresh("synthetic-input")).thenThrow(failure);
        int code=failure instanceof UserNotFoundException?400:((AuthSessionRejectedException)failure).reason()==AuthSessionRejectedException.Reason.MISSING?404:401;
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"synthetic-input\"}"))
                .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").value(failure.getMessage())).andExpect(jsonPath("$.data").isEmpty());
    }
    static Stream<RuntimeException> failures(){return Stream.of(new AuthSessionRejectedException(AuthSessionRejectedException.Reason.INVALID),new AuthSessionRejectedException(AuthSessionRejectedException.Reason.MISSING),new AuthSessionRejectedException(AuthSessionRejectedException.Reason.EXPIRED),new UserNotFoundException());}
    @ParameterizedTest @ValueSource(strings={"missing","invalid","refresh"})
    void given__no_valid_access_despite_identity_header__when__logout__then__401_before_service(String kind) throws Exception {
        var request=post("/api/v1/auth/logout").header("X-User-Id","999").header("X-User-Role","ROLE_ADMIN");
        if(kind.equals("invalid"))request.header("Authorization","Bearer invalid");
        if(kind.equals("refresh"))request.header("Authorization","Bearer "+io.jsonwebtoken.Jwts.builder().expiration(new java.util.Date(System.currentTimeMillis()+600000)).signWith(F3TestInputs.KEY).compact());
        mvc.perform(request).andExpect(status().isUnauthorized());verifyNoInteractions(sessions);
    }
    @Test void given__valid_access_and_forged_headers__when__logout_repeatedly__then__only_principal_id_and_original_success() throws Exception {
        String token=F3TestInputs.token(7,"ROLE_USER");
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+token).header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"로그아웃되었습니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verify(sessions,times(2)).logout(7);verifyNoMoreInteractions(sessions);
    }
    @Test void given__malformed_json_and_commit_failure__when__posting__then__400_or_safe500_without_token_messages(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{not-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
        when(sessions.refresh("synthetic-input")).thenThrow(new TransactionSystemException("synthetic-private-token",new RuntimeException("synthetic-private-sql")));
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content("{\"refreshToken\":\"synthetic-input\"}"))
                .andExpect(status().isInternalServerError()).andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",JsonCompareMode.STRICT));
        assertThat(output.getAll()).contains("Auth session request failed (TransactionSystemException)").doesNotContain("synthetic-private-token","synthetic-private-sql");
    }
    @ParameterizedTest @ValueSource(strings={"GET /api/v1/auth/refresh","POST /api/v1/auth/refresh/extra","POST /api/v1/auth/logout/extra","POST /api/v1/auth/login","POST /api/auth/logout"})
    void given__adjacent_or_unported_auth_route__when__requesting__then__deny_before_session_service(String spec) throws Exception {
        var split=spec.split(" ",2);mvc.perform(request(org.springframework.http.HttpMethod.valueOf(split[0]),split[1]).header("X-User-Id","7"))
                .andExpect(status().isForbidden());verifyNoInteractions(sessions);
    }
}

@WebMvcTest(controllers=AuthSessionController.class,
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ContextConfiguration(classes={AuthSessionController.class,AuthSessionExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AuthSessionDisabledHttpTest {
    @Autowired MockMvc mvc;@MockitoBean AuthSessions sessions;
    @ParameterizedTest @ValueSource(strings={"/api/v1/auth/refresh","/api/v1/auth/logout"})
    void given__default_off_despite_token_and_identity__when__posting__then__preserve_default403_without_service(String path)throws Exception{
        mvc.perform(post(path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")).header("X-User-Id","999"))
                .andExpect(status().isForbidden());verifyNoInteractions(sessions);
    }
}
