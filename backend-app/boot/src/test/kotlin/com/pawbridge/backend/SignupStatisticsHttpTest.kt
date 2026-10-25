package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.SignupPeriods
import com.pawbridge.backend.user.internal.statistics.web.SignupStatisticsController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

    @MockitoBean
    lateinit var statistics: SignupStatisticsQuery

    @Test
    fun given__admin_and_sparse_daily_rows__when__reading_explicit_dates__then__return_exact_raw_dates_counts_and_envelope() {
        val start = LocalDate.of(2024, 2, 27)
        val end = LocalDate.of(2024, 2, 29)
        whenever(statistics.daily(start, end))
            .thenReturn(listOf(DailySignupStats(start, Long.MAX_VALUE), DailySignupStats(end, 2L)))

        mvc.perform(
            get(DAILY)
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER"),
        )
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
        verify(statistics).daily(start, end)
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @Test
    fun given__admin_and_four_period_projections__when__reading__then__serialize_only_original_four_lists_and_zero_counts() {
        val day = DailySignupStats(LocalDate.of(2024, 3, 1), 0L)
        whenever(statistics.periods()).thenReturn(SignupPeriods(listOf(day), listOf(day), listOf(day), listOf(day)))

        mvc.perform(get(PERIODS).header("Authorization", admin()))
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
        verify(statistics).periods()
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = [DAILY, PERIODS])
    fun given__signed_user_and_forged_admin_header__when__requesting_stats_with_invalid_dates__then__deny_before_binding_or_query(
        path: String,
    ) {
        mvc.perform(
            get(path)
                .param("startDate", "bad")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    fun given__missing_or_invalid_token__when__reading_bad_dates__then__authenticate_before_binding(
        authorization: String?,
        message: String,
    ) {
        val request = get(DAILY).param("startDate", "bad").header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(
                content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["startDate", "endDate"])
    fun given__admin_without_one_required_date__when__reading_daily__then__return_original_missing_parameter_400_before_query(
        missing: String,
    ) {
        val present = if (missing == "startDate") "endDate" else "startDate"
        mvc.perform(get(DAILY).param(present, "2024-02-29").header("Authorization", admin()))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"필수 요청 파라미터 '" + missing + "'(이)가 누락되었습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["startDate", "endDate"])
    fun given__admin_with_unconvertible_date__when__binding__then__retain_original_runtime_500_family(invalid: String) {
        mvc.perform(
            get(DAILY)
                .param("startDate", if (invalid == "startDate") "not-a-date" else "2024-02-29")
                .param("endDate", if (invalid == "endDate") "not-a-date" else "2024-02-29")
                .header("Authorization", admin()),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verifyNoInteractions(statistics)
        cleared()
    }

    @Test
    fun given__reversed_dates_and_no_rows__when__admin_reads_daily__then__preserve_empty_success_without_new_range_validation() {
        val start = LocalDate.of(2024, 3, 1)
        val end = start.minusDays(1)
        whenever(statistics.daily(start, end)).thenReturn(listOf())
        mvc.perform(
            get(DAILY)
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .header("Authorization", admin()),
        )
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(statistics).daily(start, end)
        verifyNoMoreInteractions(statistics)
        cleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["daily", "periods"])
    fun given__query_or_commit_failure__when__admin_reads__then__return_safe_original_500_and_clear_context(
        operation: String,
    ) {
        val failure: RuntimeException = if (operation == "daily") {
            DataAccessResourceFailureException("synthetic-private-sql")
        } else {
            TransactionSystemException("synthetic-private-commit")
        }
        if (operation == "daily") {
            whenever(statistics.daily(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29))).thenThrow(failure)
        } else {
            whenever(statistics.periods()).thenThrow(failure)
        }
        val request = get(if (operation == "daily") DAILY else PERIODS).header("Authorization", admin())
        if (operation == "daily") {
            request.param("startDate", "2024-02-29").param("endDate", "2024-02-29")
        }
        mvc.perform(request)
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        if (operation == "daily") {
            verify(statistics).daily(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29))
        } else {
            verify(statistics).periods()
        }
        verifyNoMoreInteractions(statistics)
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
    fun given__unported_neighbor_or_method__when__admin_requests__then__keep_default_deny(wire: String) {
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
