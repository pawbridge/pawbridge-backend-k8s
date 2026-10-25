package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.BoardTypeStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.DailyPostStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.PostPeriodStatsResponse
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsController
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
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
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

@WebMvcTest(
    controllers = [AdminPostStatisticsController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminPostStatisticsController::class,
        AdminPostStatisticsExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AdminPostStatisticsHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var statistics: AdminPostStatisticsQuery

    @Test
    fun `관리자와 Long 오늘 건수__조회하면__원래 Long 봉투를 돌려주고 신원 헤더를 무시한다`() {
        // given
        every { statistics.today() } returns Long.MAX_VALUE

        // when
        val result = mvc.perform(
            get("$ROOT/today")
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER")
                .header("X-User-Id", "999"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":9223372036854775807}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { statistics.today() }
        confirmVerified(statistics)
        cleared()
    }

    @Test
    fun `관리자와 성긴 기간 집계__조회하면__채우기나 정렬 없이 원래 필드만 돌려준다`() {
        // given
        every { statistics.period(any(), any()) } returns PostPeriodStatsResponse(
            DAY.minusDays(2),
            DAY,
            listOf(DailyPostStats(DAY.minusDays(2), Long.MAX_VALUE), DailyPostStats(DAY, 2)),
            3,
            listOf(BoardTypeStats("REPORT", 2), BoardTypeStats("ADOPTION", Long.MAX_VALUE)),
        )

        // when
        val result = mvc.perform(
            get("$ROOT/period")
                .param("startDate", "2024-02-28")
                .param("endDate", "2024-03-01")
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    """
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
                      "startDate":"2024-02-28","endDate":"2024-03-01","daily":[{"date":"2024-02-28","count":9223372036854775807},{"date":"2024-03-01","count":2}],
                      "previousDayCount":3,"byBoardType":[{"boardType":"REPORT","count":2},{"boardType":"ADOPTION","count":9223372036854775807}]}}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { statistics.period(DAY.minusDays(2), DAY) }
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/today", "/period"])
    fun `사용자 토큰과 위조 관리자 헤더__잘못된 날짜로 요청하면__바인딩이나 조회 전에 거부한다`(
        suffix: String,
    ) {
        // given
        val request = get(ROOT + suffix)
            .param("startDate", "bad")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .header("X-User-Role", "ROLE_ADMIN")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun `없거나 잘못된 Bearer__잘못된 날짜로 요청하면__바인딩 전에 인증한다`(token: String) {
        // given
        val request = get("$ROOT/period").param("startDate", "bad").header("X-User-Role", "ROLE_ADMIN")
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        val message = if (token == "missing") "인증 토큰이 필요합니다." else "유효하지 않거나 만료된 토큰입니다."

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["startDate", "endDate", "malformed"])
    fun `누락되었거나 잘못된 날짜__바인딩하면__조회 없이 서로 다른 원래 400 본문을 유지한다`(
        defect: String,
    ) {
        // given
        val request = get("$ROOT/period").header("Authorization", admin())
        if (defect != "startDate") {
            request.param("startDate", if (defect == "malformed") "2024-02-30" else DAY.toString())
        }
        if (defect != "endDate") {
            request.param("endDate", DAY.toString())
        }

        // when
        val result = mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))

        // then
        if (defect == "malformed") {
            result.andExpect(content().string(""))
        } else {
            result.andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 '" + defect + "'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        }
        confirmVerified(statistics)
        cleared()
    }

    @Test
    fun `업무 규칙이 거부한 기간__관리자가 조회하면__원래 사유를 봉투에 담아 돌려준다`() {
        // given
        val reason = "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다."

        every { statistics.period(any(), any()) } throws ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

        // when
        val result = mvc.perform(
            get("$ROOT/period")
                .param("startDate", DAY.toString())
                .param("endDate", DAY.minusDays(1).toString())
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json("{\"code\":400,\"message\":\"" + reason + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { statistics.period(DAY, DAY.minusDays(1)) }
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["dao", "commit", "runtime"])
    fun `조회 커밋 또는 런타임 실패__관리자가 조회하면__재시도와 내부 정보 없이 안전한 500을 유지한다`(
        phase: String,
    ) {
        // given
        val failure: RuntimeException = when (phase) {
            "dao" -> DataAccessResourceFailureException("private-sql")
            "commit" -> TransactionSystemException("private-commit")
            else -> IllegalStateException("private-runtime")
        }

        every { statistics.period(any(), any()) } throws failure

        // when
        val result = mvc.perform(
            get("$ROOT/period")
                .param("startDate", DAY.toString())
                .param("endDate", DAY.toString())
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 1) { statistics.period(DAY, DAY) }
        confirmVerified(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET /api/v1/admin/posts/stats/period/extra", "POST /api/v1/admin/posts/stats/today"])
    fun `이식되지 않은 이웃 경로나 메서드__관리자가 요청하면__기본 거부를 유지한다`(wire: String) {
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
        private const val ROOT = "/api/v1/admin/posts/stats"
        private const val CACHE = "no-cache, no-store, max-age=0, must-revalidate"
        private val DAY: LocalDate = LocalDate.of(2024, 3, 1)

        private fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
