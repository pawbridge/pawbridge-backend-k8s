package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.SignupPeriods
import com.pawbridge.backend.user.internal.statistics.web.SignupStatisticsController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDate
import java.util.stream.Stream

@WebMvcTest(
    controllers = [SignupStatisticsController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        SignupStatisticsController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class SignupStatisticsHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var statistics: SignupStatisticsQuery

    @Test
    fun `관리자와 듬성한 일별 행이 주어졌을 때__명시 날짜로 조회하면__원본 날짜와 건수와 봉투를 그대로 돌려준다`() {
        // given
        val start = LocalDate.of(2024, 2, 27)
        val end = LocalDate.of(2024, 2, 29)

        every { statistics.daily(any(), any()) } returns
            listOf(DailySignupStats(start, Long.MAX_VALUE), DailySignupStats(end, 2L))

        // when
        val result = mvc.perform(
            get(DAILY)
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    """
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":[
                      {"date":"2024-02-27","count":9223372036854775807},{"date":"2024-02-29","count":2}]}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { statistics.daily(start, end) }
        confirmVerified(statistics)
        cleared()
    }

    @Test
    fun `관리자와 네 기간 집계가 주어졌을 때__조회하면__원래 네 목록과 0 건수만 직렬화한다`() {
        // given
        val day = DailySignupStats(LocalDate.of(2024, 3, 1), 0L)

        every { statistics.periods() } returns SignupPeriods(listOf(day), listOf(day), listOf(day), listOf(day))

        // when
        val result = mvc.perform(get(PERIODS).header("Authorization", admin()))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    """
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
                      "today":[{"date":"2024-03-01","count":0}],"last7Days":[{"date":"2024-03-01","count":0}],
                      "last30Days":[{"date":"2024-03-01","count":0}],"thisMonth":[{"date":"2024-03-01","count":0}]}}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { statistics.periods() }
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = [DAILY, PERIODS])
    fun `일반 사용자와 위조된 관리자 헤더가 주어졌을 때__잘못된 날짜로 통계를 요청하면__바인딩과 조회 전에 거부한다`(
        path: String,
    ) {
        // given
        val token = F3TestInputs.token(7, "ROLE_USER")

        // when
        val result = mvc.perform(
            get(path)
                .param("startDate", "bad")
                .header("Authorization", "Bearer $token")
                .header("X-User-Role", "ROLE_ADMIN"),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun `토큰이 없거나 유효하지 않을 때__잘못된 날짜로 조회하면__바인딩 전에 인증한다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = get(DAILY).param("startDate", "bad").header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["startDate", "endDate"])
    fun `관리자가 필수 날짜 하나를 빼먹었을 때__일별 통계를 조회하면__조회 전에 원래의 누락 파라미터 400을 돌려준다`(
        missing: String,
    ) {
        // given
        val present = if (missing == "startDate") "endDate" else "startDate"

        // when
        val result = mvc.perform(get(DAILY).param(present, "2024-02-29").header("Authorization", admin()))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 '" + missing + "'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["startDate", "endDate"])
    fun `관리자가 변환할 수 없는 날짜를 보냈을 때__바인딩하면__원래의 런타임 500 계열을 유지한다`(invalid: String) {
        // given
        val startDate = if (invalid == "startDate") "not-a-date" else "2024-02-29"
        val endDate = if (invalid == "endDate") "not-a-date" else "2024-02-29"

        // when
        val result = mvc.perform(
            get(DAILY)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        confirmVerified(statistics)
        cleared()
    }

    @Test
    fun `날짜 범위가 뒤집히고 행이 없을 때__관리자가 일별 통계를 조회하면__새 범위 검증 없이 빈 성공을 유지한다`() {
        // given
        val start = LocalDate.of(2024, 3, 1)
        val end = start.minusDays(1)

        every { statistics.daily(any(), any()) } returns listOf()

        // when
        val result = mvc.perform(
            get(DAILY)
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { statistics.daily(start, end) }
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["daily", "periods"])
    fun `조회나 커밋이 실패할 때__관리자가 읽으면__안전한 원래의 500을 돌려주고 컨텍스트를 비운다`(
        operation: String,
    ) {
        // given
        val failure: RuntimeException = if (operation == "daily") {
            DataAccessResourceFailureException("synthetic-private-sql")
        } else {
            TransactionSystemException("synthetic-private-commit")
        }
        val request = get(if (operation == "daily") DAILY else PERIODS).header("Authorization", admin())
        if (operation == "daily") {
            request.param("startDate", "2024-02-29").param("endDate", "2024-02-29")
        }

        if (operation == "daily") {
            every { statistics.daily(any(), any()) } throws failure
        } else {
            every { statistics.periods() } throws failure
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        if (operation == "daily") {
            verify(exactly = 1) { statistics.daily(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)) }
        } else {
            verify(exactly = 1) { statistics.periods() }
        }
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/admin/users/stats/daily-signups/extra",
            "POST /api/v1/admin/users/stats/signup-periods",
            "GET /api/v1/admin/users/stats/not-ported",
        ],
    )
    fun `이식되지 않은 이웃 경로나 메서드일 때__관리자가 요청하면__기본 거부를 유지한다`(wire: String) {
        // given
        val parts = wire.split(" ", limit = 2)

        // when
        val result = mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", admin()))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(statistics)
        cleared()
    }

    companion object {
        private const val DAILY = "/api/v1/admin/users/stats/daily-signups"
        private const val PERIODS = "/api/v1/admin/users/stats/signup-periods"
        private const val CACHE = "no-cache, no-store, max-age=0, must-revalidate"

        @JvmStatic
        fun rejectedTokens(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
