package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.BoardTypeStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.DailyPostStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.PostPeriodStatsResponse
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsController
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var statistics: AdminPostStatisticsQuery

    @Test
    fun given__admin_and_long_today_count__when__reading__then__return_the_original_long_envelope_and_ignore_identity_headers() {
        whenever(statistics.today()).thenReturn(Long.MAX_VALUE)
        mvc.perform(
            get("$ROOT/today")
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER")
                .header("X-User-Id", "999"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":9223372036854775807}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(statistics).today()
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @Test
    fun given__admin_and_sparse_period_projection__when__reading__then__return_only_original_fields_without_filling_or_sorting() {
        whenever(statistics.period(DAY.minusDays(2), DAY)).thenReturn(
            PostPeriodStatsResponse(
                DAY.minusDays(2),
                DAY,
                listOf(DailyPostStats(DAY.minusDays(2), Long.MAX_VALUE), DailyPostStats(DAY, 2)),
                3,
                listOf(BoardTypeStats("REPORT", 2), BoardTypeStats("ADOPTION", Long.MAX_VALUE)),
            ),
        )
        mvc.perform(
            get("$ROOT/period")
                .param("startDate", "2024-02-28")
                .param("endDate", "2024-03-01")
                .header("Authorization", admin()),
        )
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
        verify(statistics).period(DAY.minusDays(2), DAY)
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/today", "/period"])
    fun given__user_token_and_forged_admin_header__when__requesting_invalid_dates__then__deny_before_binding_or_query(
        suffix: String,
    ) {
        mvc.perform(
            get(ROOT + suffix)
                .param("startDate", "bad")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun given__missing_or_invalid_bearer__when__requesting_bad_dates__then__authenticate_before_binding(token: String) {
        val request = get("$ROOT/period").param("startDate", "bad").header("X-User-Role", "ROLE_ADMIN")
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        val message = if (token == "missing") "인증 토큰이 필요합니다." else "유효하지 않거나 만료된 토큰입니다."
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["startDate", "endDate", "malformed"])
    fun given__missing_or_malformed_date__when__binding__then__keep_the_distinct_original_400_bodies_without_query(
        defect: String,
    ) {
        val request = get("$ROOT/period").header("Authorization", admin())
        if (defect != "startDate") {
            request.param("startDate", if (defect == "malformed") "2024-02-30" else DAY.toString())
        }
        if (defect != "endDate") {
            request.param("endDate", DAY.toString())
        }
        val result = mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
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
        verifyNoInteractions(statistics)
        cleared()
    }

    @Test
    fun given__business_rejected_range__when__admin_reads__then__return_original_reason_in_envelope() {
        val reason = "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다."
        whenever(statistics.period(DAY, DAY.minusDays(1))).thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, reason))
        mvc.perform(
            get("$ROOT/period")
                .param("startDate", DAY.toString())
                .param("endDate", DAY.minusDays(1).toString())
                .header("Authorization", admin()),
        )
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json("{\"code\":400,\"message\":\"" + reason + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(statistics).period(DAY, DAY.minusDays(1))
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["dao", "commit", "runtime"])
    fun given__query_commit_or_runtime_failure__when__admin_reads__then__retain_safe_500_without_retry_or_private_details(
        phase: String,
    ) {
        val failure: RuntimeException = when (phase) {
            "dao" -> DataAccessResourceFailureException("private-sql")
            "commit" -> TransactionSystemException("private-commit")
            else -> IllegalStateException("private-runtime")
        }
        whenever(statistics.period(DAY, DAY)).thenThrow(failure)
        mvc.perform(
            get("$ROOT/period")
                .param("startDate", DAY.toString())
                .param("endDate", DAY.toString())
                .header("Authorization", admin()),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(statistics).period(DAY, DAY)
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET /api/v1/admin/posts/stats/period/extra", "POST /api/v1/admin/posts/stats/today"])
    fun given__an_unported_neighbor_or_method__when__admin_requests__then__keep_default_denial(wire: String) {
        val parts = wire.split(" ", limit = 2)
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", admin()))
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(statistics)
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
