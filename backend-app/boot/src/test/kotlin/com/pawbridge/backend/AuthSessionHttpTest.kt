package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.web.AuthSessionController
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.util.Date

@WebMvcTest(
    controllers = [AuthSessionController::class],
    properties = ["pawbridge.auth.sessions-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(
    classes = [
        AuthSessionHttpTest.Controller::class,
        AuthSessionExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class AuthSessionHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var sessions: AuthSessions

    class Controller {
        @Bean
        fun authSessionController(sessions: AuthSessions): AuthSessionController {
            return AuthSessionController(sessions, listOf(ORIGIN, "https://pawbridge.kr"))
        }
    }

    @Test
    fun `허용된 페이지가 리프레시 쿠키를 가질 때__갱신하면__접근 토큰만 돌려주고 쿠키를 교체한다`() {
        // given
        every { sessions.refresh(any()) } returns AuthSessions.Rotated("synthetic-access", IssuedRefreshToken(NEXT, 1_209_600))

        // when
        val result = mvc.perform(
            browser("/api/v1/auth/refresh")
                .cookie(Cookie("__Secure-pb_refresh", PRESENTED))
                .header("Authorization", "Bearer invalid"),
        )
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"토큰이 재발급되었습니다.\",\"data\":{\"accessToken\":\"synthetic-access\"}}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn()

        // then
        val cookie = result.response.getHeader("Set-Cookie")
        assertThat(cookie).startsWith("__Secure-pb_refresh=" + NEXT + "; Path=/api/v1/auth; Max-Age=1209600; Expires=")
            .endsWith("; Secure; HttpOnly; SameSite=Lax")
            .doesNotContain("Domain=")
        assertThat(result.response.contentAsString).doesNotContain(NEXT).doesNotContain("refreshToken")
        verify(exactly = 1) { sessions.refresh(PRESENTED) }
        confirmVerified(sessions)
    }

    @Test
    fun `동시 갱신이 이미 쿠키를 교체했을 때__갱신하면__쿠키를 건드리지 않고 접근 토큰만 돌려준다`() {
        // given
        every { sessions.refresh(any()) } returns AuthSessions.AccessOnly("synthetic-access")

        // when
        val response = mvc.perform(browser("/api/v1/auth/refresh").cookie(Cookie("__Secure-pb_refresh", PRESENTED)))

        // then
        response.andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"토큰이 재발급되었습니다.\",\"data\":{\"accessToken\":\"synthetic-access\"}}",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @Test
    fun `쿠키 없이 옛 갱신 본문만 있을 때__갱신하면__본문을 무시하고 없음으로 거부한다`() {
        // given
        every { sessions.refresh(any()) } throws AuthSessionRejectedException(Reason.MISSING)

        // when
        val response = mvc.perform(
            browser("/api/v1/auth/refresh").content("{\"refreshToken\":\"" + PRESENTED + "\"}"),
        )

        // then
        response.andExpect(status().isUnauthorized())
            .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
            .andExpect(
                content().json(
                    "{\"code\":401,\"message\":\"리프레시 토큰을 찾을 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { sessions.refresh(null) }
    }

    @ParameterizedTest
    @EnumSource(value = Reason::class, names = ["INVALID", "REUSED"])
    fun `잘못되거나 재사용된 쿠키일 때__갱신하면__401과 함께 쿠키를 지운다`(reason: Reason) {
        // given
        every { sessions.refresh(any()) } throws AuthSessionRejectedException(reason)

        // when
        val response = mvc.perform(browser("/api/v1/auth/refresh").cookie(Cookie("__Secure-pb_refresh", PRESENTED)))

        // then
        response.andExpect(status().isUnauthorized())
            .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"유효하지 않은 토큰입니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "missing-origin", "other-origin", "wildcard-sibling", "null-origin", "missing-type",
            "form-type", "text-type", "malformed-type",
        ],
    )
    fun `허용된 페이지가 보낼 수 없는 요청일 때__갱신하거나 로그아웃하면__세션 서비스 전에 403을 돌려준다`(
        kind: String,
    ) {
        for (path in listOf("/api/v1/auth/refresh", "/api/v1/auth/logout")) {
            // given
            val request = post(path).cookie(Cookie("__Secure-pb_refresh", PRESENTED)).content("{}")
            when (kind) {
                "missing-origin" -> request.contentType("application/json")
                "other-origin" -> request.header("Origin", "https://evil.example").contentType("application/json")
                "wildcard-sibling" -> request.header("Origin", "https://shop.pawbridge.kr").contentType("application/json")
                "null-origin" -> request.header("Origin", "null").contentType("application/json")
                "missing-type" -> request.header("Origin", ORIGIN)
                "form-type" -> request.header("Origin", ORIGIN).contentType("application/x-www-form-urlencoded")
                "text-type" -> request.header("Origin", ORIGIN).contentType("text/plain")
                else -> request.header("Origin", ORIGIN).header("Content-Type", "application/")
            }

            // when
            val response = mvc.perform(request)

            // then
            response.andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(
                    content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
                )
        }
        confirmVerified(sessions)
    }

    @Test
    fun `두 번째 허용 출처가 charset 있는 JSON을 보낼 때__갱신하면__받아들인다`() {
        // given
        every { sessions.refresh(any()) } returns AuthSessions.AccessOnly("synthetic-access")

        // when
        val response = mvc.perform(
            post("/api/v1/auth/refresh")
                .header("Origin", "https://pawbridge.kr")
                .contentType("application/json;charset=UTF-8")
                .cookie(Cookie("__Secure-pb_refresh", PRESENTED)),
        )

        // then
        response.andExpect(status().isOk())
    }

    @Test
    fun `접근 토큰이 만료됐거나 없을 때__반복해서 로그아웃하면__쿠키로 폐기하고 항상 쿠키를 지운다`() {
        // given
        val expired = Jwts.builder()
            .claim("userId", 7)
            .expiration(Date(System.currentTimeMillis() - 60_000))
            .signWith(F3TestInputs.KEY)
            .compact()

        justRun { sessions.logout(any()) }

        // when
        val repeated = (0 until 2).map {
            mvc.perform(
                browser("/api/v1/auth/logout")
                    .cookie(Cookie("__Secure-pb_refresh", PRESENTED))
                    .header("Authorization", "Bearer $expired"),
            )
        }
        val withoutCookie = mvc.perform(browser("/api/v1/auth/logout"))

        // then
        for (response in repeated) {
            response.andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
                .andExpect(
                    content().json("{\"code\":200,\"message\":\"로그아웃되었습니다.\",\"data\":null}", JsonCompareMode.STRICT),
                )
        }
        withoutCookie.andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", CLEAR_COOKIE))
        verify(exactly = 2) { sessions.logout(PRESENTED) }
        verify(exactly = 1) { sessions.logout(null) }
        confirmVerified(sessions)
    }

    @Test
    fun `데이터베이스 실패일 때__갱신하면__쿠키를 유지한 안전한 500이고 세부 정보를 로그에 남기지 않는다`(
        output: CapturedOutput,
    ) {
        // given
        every { sessions.refresh(any()) } throws
            TransactionSystemException("synthetic-private-token", RuntimeException("synthetic-private-sql"))
        every { sessions.logout(any()) } throws TransactionSystemException("synthetic-private-token")

        // when
        val refresh = mvc.perform(browser("/api/v1/auth/refresh").cookie(Cookie("__Secure-pb_refresh", PRESENTED)))
        val logout = mvc.perform(browser("/api/v1/auth/logout").cookie(Cookie("__Secure-pb_refresh", PRESENTED)))

        // then
        refresh.andExpect(status().isInternalServerError())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        logout.andExpect(status().isInternalServerError())
        assertThat(output.all).contains("Auth session request failed (TransactionSystemException)")
            .doesNotContain("synthetic-private-token", "synthetic-private-sql", PRESENTED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/auth/refresh", "/api/v1/auth/logout"])
    fun `사이트의 브라우저 사전 요청일 때__자격 증명 JSON POST를 물으면__정확히 그 출처만 허용한다`(
        path: String,
    ) {
        // when
        val response = mvc.perform(
            options(path)
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"),
        )

        // then
        response.andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        confirmVerified(sessions)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/auth/refresh", "POST /api/v1/auth/refresh/extra", "POST /api/v1/auth/logout/extra",
            "POST /api/v1/auth/login", "POST /api/auth/logout",
        ],
    )
    fun `인접하거나 이식되지 않은 인증 경로일 때__요청하면__세션 서비스 전에 거부한다`(spec: String) {
        // given
        val split = spec.split(" ", limit = 2)

        // when
        val response = mvc.perform(
            request(HttpMethod.valueOf(split[0]), split[1]).header("X-User-Id", "7").header("Origin", ORIGIN),
        )

        // then
        response.andExpect(status().isForbidden())
        confirmVerified(sessions)
    }

    companion object {
        const val ORIGIN = "https://www.pawbridge.kr"
        const val PRESENTED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val NEXT = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        const val CLEAR_COOKIE = "__Secure-pb_refresh=; Path=/api/v1/auth; Max-Age=0; " +
            "Expires=Thu, 1 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax"

        private fun browser(path: String): MockHttpServletRequestBuilder {
            return post(path).header("Origin", ORIGIN).contentType("application/json").content("{}")
        }
    }
}

@WebMvcTest(
    controllers = [AuthSessionController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(
    classes = [
        AuthSessionHttpTest.Controller::class,
        AuthSessionExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AuthSessionDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var sessions: AuthSessions

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/auth/refresh", "/api/v1/auth/logout"])
    fun `기본 꺼짐에 쿠키와 토큰이 있을 때__POST하면__서비스 없이 기본 403을 유지한다`(path: String) {
        // when
        val response = mvc.perform(
            post(path)
                .header("Origin", AuthSessionHttpTest.ORIGIN)
                .contentType("application/json")
                .cookie(Cookie("__Secure-pb_refresh", AuthSessionHttpTest.PRESENTED))
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )

        // then
        response.andExpect(status().isForbidden())
        confirmVerified(sessions)
    }
}
