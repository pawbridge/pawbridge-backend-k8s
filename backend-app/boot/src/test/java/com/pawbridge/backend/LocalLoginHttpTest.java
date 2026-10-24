package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties={"pawbridge.auth.local-enabled=true","pawbridge.auth.sessions-enabled=true","logging.level.org.springframework.security=DEBUG"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ContextConfiguration(classes={LocalLoginConfiguration.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class,LocalLoginHttpTest.Inputs.class})
@ExtendWith(OutputCaptureExtension.class)
class LocalLoginHttpTest {
    private static final String PASSWORD="synthetic-local-password";
    private static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String PRIVATE="synthetic-private-login-failure-marker";
    private static final JwtSessionTokens TOKENS=new JwtSessionTokens(F3TestInputs.KEY,600000);
    private static final String REFRESH="CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
    @Autowired MockMvc mvc;
    @MockitoBean LocalLogin login;

    @ParameterizedTest @ValueSource(strings={"POST","GET"})
    void given__valid_credentials_and_forged_identity__when__original_exact_login__then__real_bcrypt_access_in_body_and_refresh_only_in_cookie(String method) throws Exception {
        String role = method.equals("POST") ? "ROLE_USER" : "ROLE_SHELTER";
        String care = method.equals("POST") ? null : "synthetic-care";
        var account = account(HASH, role, care);
        when(login.findByEmail("MiXeD@example.invalid")).thenReturn(Optional.of(account));
        when(login.complete(account)).thenAnswer(invocation -> new LocalLogin.CompletedLogin(
                TOKENS.access(new JwtSessionTokens.UserClaims(7, account.email(), account.name(), role, care)),
                new IssuedRefreshToken(REFRESH, 1_209_600)));

        var result = mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/auth/login").contentType("application/json")
                        .content("{\"email\":\"MiXeD@example.invalid\",\"password\":\"" + PASSWORD + "\"}")
                        .header("Authorization", "Bearer invalid").header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Authorization"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
                .andExpect(jsonPath("$.data.userId").value(7)).andExpect(jsonPath("$.data.email").value(account.email()))
                .andExpect(jsonPath("$.data.name").value(account.name())).andExpect(jsonPath("$.data.role").value(role))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();

        var data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.size()).isEqualTo(6);
        if (care == null) {
            assertThat(data.path("careRegNo").isNull()).isTrue();
        } else {
            assertThat(data.path("careRegNo").asText()).isEqualTo(care);
        }
        var parser = io.jsonwebtoken.Jwts.parser().verifyWith(F3TestInputs.KEY).build();
        var access = parser.parseSignedClaims(data.path("accessToken").asText()).getPayload();
        assertThat(access.get("userId", Number.class).longValue()).isEqualTo(7);
        assertThat(access.get("role", String.class)).isEqualTo(role);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(REFRESH);

        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).startsWith("__Secure-pb_refresh=" + REFRESH + "; Path=/api/v1/auth; Max-Age=1209600; Expires=")
                .endsWith("; Secure; HttpOnly; SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(login).findByEmail("MiXeD@example.invalid");
        verify(login).complete(account);
        verifyNoMoreInteractions(login);
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong","oauth-placeholder","null-stored","missing-password"})
    void given__unknown_or_bad_credentials__when__login__then__same_hidden401_and_no_token_storage(String kind)throws Exception{
        var account=account(kind.equals("oauth-placeholder")?"OAuth2":kind.equals("null-stored")?null:HASH,"ROLE_USER",null);
        when(login.findByEmail("synthetic@example.invalid")).thenReturn(kind.equals("missing")?Optional.empty():Optional.of(account));
        String input=kind.equals("wrong")?"wrong-synthetic-password":kind.equals("missing-password")?null:PASSWORD;
        var body=new ObjectMapper().createObjectNode().put("email","synthetic@example.invalid");if(input!=null)body.put("password",input);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body.toString()))
                .andExpect(status().isUnauthorized()).andExpect(content().json("{\"code\":401,\"message\":\"이메일 또는 비밀번호가 일치하지 않습니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verify(login,never()).complete(any());
    }
    @Test void given__email_not_mvc_validated__when__original_filter_login__then__do_not_add_an_email_policy()throws Exception{
        var account=account(HASH,"ROLE_USER",null);when(login.findByEmail("not-email")).thenReturn(Optional.of(account));
        when(login.complete(account)).thenReturn(new LocalLogin.CompletedLogin("synthetic-access",new IssuedRefreshToken(REFRESH,60)));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"not-email\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk());verify(login).complete(account);
    }
    @Test void given__account_lookup_failure__when__dao_authentication__then__original_generic401_without_private_cause_in_logs(CapturedOutput output)throws Exception{
        when(login.findByEmail(anyString())).thenThrow(new DataAccessResourceFailureException(PRIVATE));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"synthetic@example.invalid\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isUnauthorized()).andExpect(content().json("{\"code\":401,\"message\":\"인증에 실패했습니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verify(login,never()).complete(any());assertThat(output.getAll()).doesNotContain(PRIVATE,PASSWORD,HASH);
    }
    @Test void given__refresh_storage_failure_after_valid_password__when__login__then__propagate_original_failure_without_tokens_or_private_cause(CapturedOutput output)throws Exception{
        var account=account(HASH,"ROLE_USER",null);when(login.findByEmail(anyString())).thenReturn(Optional.of(account));
        when(login.complete(account)).thenThrow(new DataAccessResourceFailureException(PRIVATE));
        Throwable failure=catchThrowable(()->mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"synthetic@example.invalid\",\"password\":\""+PASSWORD+"\"}")));
        assertThat(failure).isNotNull();assertThat(trace(failure)).doesNotContain(PRIVATE,PASSWORD,HASH);assertThat(output.getAll()).doesNotContain(PRIVATE,PASSWORD,HASH);
    }
    @Test void given__malformed_json_with_private_input__when__login__then__original_filter_exception_without_body_cause(CapturedOutput output){
        Throwable failure=catchThrowable(()->mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"password\":\""+PRIVATE+"\"")));
        assertThat(failure).isNotNull();assertThat(trace(failure)).doesNotContain(PRIVATE);assertThat(output.getAll()).doesNotContain(PRIVATE);verifyNoInteractions(login);
    }
    // Security chain only: LegacyApiPathFilter (the temporary /api -> /api/v1 rewrite) is a servlet filter outside it, so the old alias stays denied here; the rewrite is covered by GatewayFeaturesHttpTest.
    @ParameterizedTest @ValueSource(strings={"/api/v1/auth/login/extra","/api/v1/auth/login/","/api/auth/login"})
    void given__neighboring_route__when__requesting__then__no_login_or_policy_widening(String path)throws Exception{
        mvc.perform(post(path).contentType("application/json").content("{}")) .andExpect(status().isForbidden());verifyNoInteractions(login);
    }
    private static LocalLogin.Account account(String password,String role,String care){return new LocalLogin.Account(7,"synthetic@example.invalid","synthetic-name",password,role,care);}
    private static String trace(Throwable failure){var text=new StringWriter();failure.printStackTrace(new PrintWriter(text));return text.toString();}
    @TestConfiguration(proxyBeanMethods=false) static class Inputs{
        @Bean JwtSessionTokens sessionTokens(){return TOKENS;}
        @Bean JdbcAuthSessionStore authSessionStore(){return mock(JdbcAuthSessionStore.class);}
        @Bean("writeJdbc") JdbcTemplate jdbc(){return mock(JdbcTemplate.class);}
        @Bean("writeTransactionManager") JdbcTransactionManager manager(){return mock(JdbcTransactionManager.class);}
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ContextConfiguration(classes={LocalLoginConfiguration.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class LocalLoginDisabledHttpTest{
    @Autowired MockMvc mvc;@MockitoBean LocalLogin login;
    @Test void given__local_login_default_off__when__credentials_and_identity_are_supplied__then__existing403_without_authentication()throws Exception{
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"synthetic@example.invalid\",\"password\":\"synthetic-password\"}")
                        .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(login);
    }
}
