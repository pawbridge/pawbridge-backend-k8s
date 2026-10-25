package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Optional

@WebMvcTest(
    properties = [
        "pawbridge.auth.local-enabled=true",
        "pawbridge.auth.sessions-enabled=true",
        "logging.level.org.springframework.security=DEBUG",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(
    classes = [
        LocalLoginConfiguration::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        LocalLoginHttpTest.Inputs::class,
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class LocalLoginHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var login: LocalLogin

    @ParameterizedTest
    @ValueSource(strings = ["POST", "GET"])
    fun given__valid_credentials_and_forged_identity__when__original_exact_login__then__real_bcrypt_access_in_body_and_refresh_only_in_cookie(
        method: String,
    ) {
        val role = if (method == "POST") "ROLE_USER" else "ROLE_SHELTER"
        val care: String? = if (method == "POST") null else "synthetic-care"
        val account = account(HASH, role, care)
        whenever(login.findByEmail("MiXeD@example.invalid")).thenReturn(Optional.of(account))
        whenever(login.complete(account)).thenAnswer {
            LocalLogin.CompletedLogin(
                TOKENS.access(JwtSessionTokens.UserClaims(7, account.email, account.name, role, care)),
                IssuedRefreshToken(REFRESH, 1_209_600),
            )
        }

        val result = mvc.perform(
            request(HttpMethod.valueOf(method), "/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"MiXeD@example.invalid\",\"password\":\"" + PASSWORD + "\"}")
                .header("Authorization", "Bearer invalid")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Authorization"))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
            .andExpect(jsonPath("$.data.userId").value(7))
            .andExpect(jsonPath("$.data.email").value(account.email))
            .andExpect(jsonPath("$.data.name").value(account.name))
            .andExpect(jsonPath("$.data.role").value(role))
            .andExpect(jsonPath("$.data.password").doesNotExist())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn()

        val data = ObjectMapper().readTree(result.response.contentAsString).path("data")
        assertThat(data.size()).isEqualTo(6)
        if (care == null) {
            assertThat(data.path("careRegNo").isNull()).isTrue()
        } else {
            assertThat(data.path("careRegNo").asText()).isEqualTo(care)
        }
        val parser = Jwts.parser().verifyWith(F3TestInputs.KEY).build()
        val access = parser.parseSignedClaims(data.path("accessToken").asText()).payload
        assertThat(access.get("userId", Number::class.java).toLong()).isEqualTo(7)
        assertThat(access.get("role", String::class.java)).isEqualTo(role)
        assertThat(result.response.contentAsString).doesNotContain(REFRESH)

        val cookie = result.response.getHeader("Set-Cookie")
        assertThat(cookie).startsWith("__Secure-pb_refresh=" + REFRESH + "; Path=/api/v1/auth; Max-Age=1209600; Expires=")
            .endsWith("; Secure; HttpOnly; SameSite=Lax")
            .doesNotContain("Domain=")
        assertThat(result.response.getHeaders("Set-Cookie")).hasSize(1)
        assertThat(result.request.getSession(false)).isNull()
        verify(login).findByEmail("MiXeD@example.invalid")
        verify(login).complete(account)
        verifyNoMoreInteractions(login)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "wrong", "oauth-placeholder", "null-stored", "missing-password"])
    fun given__unknown_or_bad_credentials__when__login__then__same_hidden401_and_no_token_storage(kind: String) {
        val stored = when (kind) {
            "oauth-placeholder" -> "OAuth2"
            "null-stored" -> null
            else -> HASH
        }
        val account = account(stored, "ROLE_USER", null)
        whenever(login.findByEmail("synthetic@example.invalid"))
            .thenReturn(if (kind == "missing") Optional.empty() else Optional.of(account))
        val input = when (kind) {
            "wrong" -> "wrong-synthetic-password"
            "missing-password" -> null
            else -> PASSWORD
        }
        val body = ObjectMapper().createObjectNode().put("email", "synthetic@example.invalid")
        if (input != null) {
            body.put("password", input)
        }
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body.toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json(
                    "{\"code\":401,\"message\":\"이메일 또는 비밀번호가 일치하지 않습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(login, never()).complete(anyNonNull<LocalLogin.Account>())
    }

    @Test
    fun given__email_not_mvc_validated__when__original_filter_login__then__do_not_add_an_email_policy() {
        val account = account(HASH, "ROLE_USER", null)
        whenever(login.findByEmail("not-email")).thenReturn(Optional.of(account))
        whenever(login.complete(account)).thenReturn(
            LocalLogin.CompletedLogin("synthetic-access", IssuedRefreshToken(REFRESH, 60)),
        )

        mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"not-email\",\"password\":\"" + PASSWORD + "\"}"),
        )
            .andExpect(status().isOk())

        verify(login).complete(account)
    }

    @Test
    fun given__account_lookup_failure__when__dao_authentication__then__original_generic401_without_private_cause_in_logs(
        output: CapturedOutput,
    ) {
        whenever(login.findByEmail(anyString())).thenThrow(DataAccessResourceFailureException(PRIVATE))
        mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"synthetic@example.invalid\",\"password\":\"" + PASSWORD + "\"}"),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json("{\"code\":401,\"message\":\"인증에 실패했습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(login, never()).complete(anyNonNull<LocalLogin.Account>())
        assertThat(output.all).doesNotContain(PRIVATE, PASSWORD, HASH)
    }

    @Test
    fun given__refresh_storage_failure_after_valid_password__when__login__then__propagate_original_failure_without_tokens_or_private_cause(
        output: CapturedOutput,
    ) {
        val account = account(HASH, "ROLE_USER", null)
        whenever(login.findByEmail(anyString())).thenReturn(Optional.of(account))
        whenever(login.complete(account)).thenThrow(DataAccessResourceFailureException(PRIVATE))
        val failure = catchThrowable {
            mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content("{\"email\":\"synthetic@example.invalid\",\"password\":\"" + PASSWORD + "\"}"),
            )
        }
        assertThat(failure).isNotNull()
        assertThat(trace(failure)).doesNotContain(PRIVATE, PASSWORD, HASH)
        assertThat(output.all).doesNotContain(PRIVATE, PASSWORD, HASH)
    }

    @Test
    fun given__malformed_json_with_private_input__when__login__then__original_filter_exception_without_body_cause(
        output: CapturedOutput,
    ) {
        val failure = catchThrowable {
            mvc.perform(
                post("/api/v1/auth/login").contentType("application/json").content("{\"password\":\"" + PRIVATE + "\""),
            )
        }
        assertThat(failure).isNotNull()
        assertThat(trace(failure)).doesNotContain(PRIVATE)
        assertThat(output.all).doesNotContain(PRIVATE)
        verifyNoInteractions(login)
    }

    // Security chain only: LegacyApiPathFilter (the temporary /api -> /api/v1 rewrite) is a servlet filter outside it, so the old alias stays denied here; the rewrite is covered by GatewayFeaturesHttpTest.
    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/auth/login/extra", "/api/v1/auth/login/", "/api/auth/login"])
    fun given__neighboring_route__when__requesting__then__no_login_or_policy_widening(path: String) {
        mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isForbidden())
        verifyNoInteractions(login)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Inputs {
        @Bean
        fun sessionTokens(): JwtSessionTokens = TOKENS

        @Bean
        fun authSessionStore(): JdbcAuthSessionStore {
            return mock(JdbcAuthSessionStore::class.java)
        }

        @Bean("writeJdbc")
        fun jdbc(): JdbcTemplate = mock(JdbcTemplate::class.java)

        @Bean("writeTransactionManager")
        fun manager(): JdbcTransactionManager = mock(JdbcTransactionManager::class.java)
    }

    companion object {
        private const val PASSWORD = "synthetic-local-password"
        private val HASH: String = BCryptPasswordEncoder(4).encode(PASSWORD)
        private const val PRIVATE = "synthetic-private-login-failure-marker"
        private val TOKENS = JwtSessionTokens(F3TestInputs.KEY, 600000)
        private const val REFRESH = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"

        private fun account(password: String?, role: String, care: String?): LocalLogin.Account {
            return LocalLogin.Account(7, "synthetic@example.invalid", "synthetic-name", password, role, care)
        }

        private fun trace(failure: Throwable): String {
            val text = StringWriter()
            failure.printStackTrace(PrintWriter(text))
            return text.toString()
        }
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(
    classes = [LocalLoginConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class LocalLoginDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var login: LocalLogin

    @Test
    fun given__local_login_default_off__when__credentials_and_identity_are_supplied__then__existing403_without_authentication() {
        mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"synthetic@example.invalid\",\"password\":\"synthetic-password\"}")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(login)
    }
}
