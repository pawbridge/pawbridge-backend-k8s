package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.visit.application.TodayVisits
import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionController
import com.pawbridge.backend.community.internal.visit.web.VisitCollectionExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

@WebMvcTest(
    controllers = [VisitCollectionController::class],
    properties = ["pawbridge.visits.enabled=true", "pawbridge.visits.collection-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        VisitCollectionController::class,
        VisitCollectionExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        VisitCollectionHttpTest.Inputs::class,
    ],
)
class VisitCollectionHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var identity: VisitIdentity

    @MockkBean
    lateinit var visits: VisitCollection

    @TestConfiguration(proxyBeanMethods = false)
    class Inputs {
        @Bean
        fun identity(): VisitIdentity = VisitIdentity(Base64.getEncoder().encodeToString(ByteArray(32)))

        @Bean
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        fun origins(): String = ORIGIN
    }

    @Test
    fun `첫 신원과 기존 신원이 주어졌을 때__초기화하면__자정까지의 호스트 쿠키를 주고 횟수는 세지 않는다`() {
        // given
        val first = post("/api/v1/visits/identity")
            .header("Origin", ORIGIN)
            .header("Authorization", "Bearer invalid")
            .header("X-User-Id", "999")

        // when
        val response = mvc.perform(first)
            .andExpect(status().isNoContent())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(""))
            .andReturn().response

        // then
        val cookie = response.getHeader("Set-Cookie")!!
        assertThat(cookie)
            .contains("__Host-pb_visit=", "Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=60")
            .doesNotContain("Domain=")
        val value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'))
        assertThat(identity.identity(value, LocalDate.of(2026, 10, 12))).isNotNull()
        mvc.perform(post("/api/v1/visits/identity").header("Origin", ORIGIN).cookie(Cookie(VisitIdentity.NAME, value)))
            .andExpect(status().isNoContent())
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(visits)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "https://outside.fixture.invalid"])
    fun `봇이어도 승인되지 않은 출처가 주어졌을 때__게시하면__쿠키나 저장 전에 403을 돌려준다`(origin: String) {
        // given
        val request = post("/api/v1/visits/identity").header("User-Agent", "crawler")
        if (origin != "missing") {
            request.header("Origin", origin)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":403,\"message\":\"허용된 출처의 요청만 가능합니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(visits)
    }

    @Test
    fun `알려진 봇이 주어졌을 때__신원을 초기화하거나 집계하면__쿠키나 저장 없이 204를 돌려준다`() {
        // given
        val paths = listOf("identity", "today")

        // when
        val results = paths.map { path ->
            mvc.perform(post("/api/v1/visits/$path").header("Origin", ORIGIN).header("User-Agent", "HeadlessChrome"))
        }

        // then
        results.forEach { result ->
            result
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(""))
        }
        confirmVerified(visits)
    }

    @Test
    fun `쿠키와 신뢰할 수 없는 신원 헤더가 주어졌을 때__집계하면__신원 반사나 발급 없이 정확한 일별 횟수를 돌려준다`() {
        // given
        val cookie = identity.initialize(null, NOW)!!.value

        every { visits.count(any()) } returns TodayVisits(LocalDate.of(2026, 10, 12), 7)

        // when
        val result = mvc.perform(
            post("/api/v1/visits/today")
                .header("Origin", ORIGIN)
                .header("X-User-Id", "999")
                .header("Authorization", "Bearer invalid")
                .cookie(Cookie(VisitIdentity.NAME, cookie)),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"date\":\"2026-10-12\",\"count\":7}}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { visits.count(cookie) }
    }

    @Test
    fun `쿠키가 없거나 모호할 때__집계하면__발급 없이 409를 돌려준다`() {
        // given
        every { visits.count(any()) } throws VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.COOKIE)
        val requests = listOf(false, true).map { duplicate ->
            val request = post("/api/v1/visits/today").header("Origin", ORIGIN)
            if (duplicate) {
                request.cookie(Cookie(VisitIdentity.NAME, "synthetic-one"), Cookie(VisitIdentity.NAME, "synthetic-two"))
            }
            request
        }

        // when
        val results = requests.map { request -> mvc.perform(request) }

        // then
        results.forEach { result ->
            result
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(
                    content().json(
                        "{\"code\":409,\"message\":\"방문 쿠키 초기화가 필요합니다.\",\"data\":null}",
                        JsonCompareMode.STRICT,
                    ),
                )
        }
        verify(exactly = 2) { visits.count(null) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "USER"])
    fun `실제 관리자 토큰이 없고 역할 헤더만 있을 때__기간을 조회하면__조회 없이 거부한다`(role: String) {
        // given
        val request = get("/api/v1/admin/visits/stats/period")
            .param("startDate", "2026-10-12")
            .param("endDate", "2026-10-12")
            .header("X-User-Role", "ROLE_ADMIN")
            .header("X-User-Id", "1")
        if (role == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        if (role == "USER") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().`is`(if (role == "USER") 403 else 401))
        confirmVerified(visits)
    }

    @Test
    fun `실제 관리자와 날짜가 주어졌을 때__기간을 조회하면__정확한 봉투와 명시적 파라미터 바인딩을 쓴다`() {
        // given
        val day = LocalDate.of(2026, 10, 12)
        val token = F3TestInputs.token(7, "ROLE_ADMIN")

        every { visits.period(any(), any()) } returns
            VisitCollection.Period(day, day, day, NOW, listOf(VisitCollection.Day(day, 7)))

        // when
        val ok = mvc.perform(
            get("/api/v1/admin/visits/stats/period")
                .header("Authorization", "Bearer $token")
                .header("X-User-Role", "ROLE_USER")
                .param("startDate", day.toString())
                .param("endDate", day.toString()),
        )
        val badDate = mvc.perform(
            get("/api/v1/admin/visits/stats/period")
                .header("Authorization", "Bearer $token")
                .param("startDate", "bad")
                .param("endDate", day.toString()),
        )
        val missingEnd = mvc.perform(
            get("/api/v1/admin/visits/stats/period")
                .header("Authorization", "Bearer $token")
                .param("startDate", day.toString()),
        )

        // then
        ok
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-12\",\"today\":\"2026-10-12\",\"collectionStartedAt\":\"2026-10-12T14:59:00Z\",\"daily\":[{\"date\":\"2026-10-12\",\"count\":7}]}}",
                    JsonCompareMode.STRICT,
                ),
            )
        badDate
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("날짜 형식을 확인해 주세요."))
        missingEnd
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("필수 요청 파라미터 'endDate'(이)가 누락되었습니다."))
        verify(exactly = 1) { visits.period(day, day) }
    }

    @Test
    fun `저장소 또는 런타임 실패가 주어졌을 때__집계하면__내부 정보 없이 안전한 오류를 돌려준다`() {
        // given
        every { visits.count(any()) } throws DataAccessResourceFailureException("synthetic-private-sql") andThenThrows
            IllegalStateException("synthetic-private-key")

        // when
        val storage = mvc.perform(post("/api/v1/visits/today").header("Origin", ORIGIN))
        val runtime = mvc.perform(post("/api/v1/visits/today").header("Origin", ORIGIN))

        // then
        storage
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"방문 통계를 불러올 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        runtime
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 2) { visits.count(null) }
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-12T14:59:00Z")
        const val ORIGIN: String = "https://www.pawbridge.kr"
    }
}
