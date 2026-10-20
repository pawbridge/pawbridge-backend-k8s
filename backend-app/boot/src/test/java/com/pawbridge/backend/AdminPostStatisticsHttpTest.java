package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery;
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.*;
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsController;
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = AdminPostStatisticsController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {AdminPostStatisticsController.class, AdminPostStatisticsExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class AdminPostStatisticsHttpTest {
    private static final String ROOT = "/api/v1/admin/posts/stats";
    private static final String CACHE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final LocalDate DAY = LocalDate.of(2024, 3, 1);
    @Autowired MockMvc mvc;
    @MockitoBean AdminPostStatisticsQuery statistics;

    @Test
    void given__admin_and_long_today_count__when__reading__then__return_the_original_long_envelope_and_ignore_identity_headers() throws Exception {
        when(statistics.today()).thenReturn(Long.MAX_VALUE);
        mvc.perform(get(ROOT + "/today").header("Authorization", admin()).header("X-User-Role", "ROLE_USER").header("X-User-Id", "999"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":9223372036854775807}", JsonCompareMode.STRICT));
        verify(statistics).today(); verifyNoMoreInteractions(statistics); cleared();
    }

    @Test
    void given__admin_and_sparse_period_projection__when__reading__then__return_only_original_fields_without_filling_or_sorting() throws Exception {
        when(statistics.period(DAY.minusDays(2), DAY)).thenReturn(new PostPeriodStatsResponse(DAY.minusDays(2), DAY,
                List.of(new DailyPostStats(DAY.minusDays(2), Long.MAX_VALUE), new DailyPostStats(DAY, 2)), 3,
                List.of(new BoardTypeStats("REPORT", 2), new BoardTypeStats("ADOPTION", Long.MAX_VALUE))));
        mvc.perform(get(ROOT + "/period").param("startDate", "2024-02-28").param("endDate", "2024-03-01").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("""
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
                      "startDate":"2024-02-28","endDate":"2024-03-01","daily":[{"date":"2024-02-28","count":9223372036854775807},{"date":"2024-03-01","count":2}],
                      "previousDayCount":3,"byBoardType":[{"boardType":"REPORT","count":2},{"boardType":"ADOPTION","count":9223372036854775807}]}}
                    """, JsonCompareMode.STRICT));
        verify(statistics).period(DAY.minusDays(2), DAY); verifyNoMoreInteractions(statistics); cleared();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/today", "/period"})
    void given__user_token_and_forged_admin_header__when__requesting_invalid_dates__then__deny_before_binding_or_query(String suffix) throws Exception {
        mvc.perform(get(ROOT + suffix).param("startDate", "bad").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")).header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(statistics); cleared();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid"})
    void given__missing_or_invalid_bearer__when__requesting_bad_dates__then__authenticate_before_binding(String token) throws Exception {
        var request = get(ROOT + "/period").param("startDate", "bad").header("X-User-Role", "ROLE_ADMIN");
        if (token.equals("invalid")) request.header("Authorization", "Bearer invalid");
        String message = token.equals("missing") ? "인증 토큰이 필요합니다." : "유효하지 않거나 만료된 토큰입니다.";
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(statistics); cleared();
    }

    @ParameterizedTest
    @ValueSource(strings = {"startDate", "endDate", "malformed"})
    void given__missing_or_malformed_date__when__binding__then__keep_the_distinct_original_400_bodies_without_query(String defect) throws Exception {
        var request = get(ROOT + "/period").header("Authorization", admin());
        if (!defect.equals("startDate")) request.param("startDate", defect.equals("malformed") ? "2024-02-30" : DAY.toString());
        if (!defect.equals("endDate")) request.param("endDate", DAY.toString());
        var result = mvc.perform(request).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", CACHE)).andExpect(header().doesNotExist("Set-Cookie"));
        if (defect.equals("malformed")) result.andExpect(content().string(""));
        else result.andExpect(content().json("{\"code\":400,\"message\":\"필수 요청 파라미터 '" + defect + "'(이)가 누락되었습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(statistics); cleared();
    }

    @Test
    void given__business_rejected_range__when__admin_reads__then__return_original_reason_in_envelope() throws Exception {
        String reason = "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.";
        when(statistics.period(DAY, DAY.minusDays(1))).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, reason));
        mvc.perform(get(ROOT + "/period").param("startDate", DAY.toString()).param("endDate", DAY.minusDays(1).toString()).header("Authorization", admin()))
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":400,\"message\":\"" + reason + "\",\"data\":null}", JsonCompareMode.STRICT));
        verify(statistics).period(DAY, DAY.minusDays(1)); verifyNoMoreInteractions(statistics); cleared();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dao", "commit", "runtime"})
    void given__query_commit_or_runtime_failure__when__admin_reads__then__retain_safe_500_without_retry_or_private_details(String phase) throws Exception {
        RuntimeException failure = switch (phase) {
            case "dao" -> new DataAccessResourceFailureException("private-sql");
            case "commit" -> new TransactionSystemException("private-commit");
            default -> new IllegalStateException("private-runtime");
        };
        when(statistics.period(DAY, DAY)).thenThrow(failure);
        mvc.perform(get(ROOT + "/period").param("startDate", DAY.toString()).param("endDate", DAY.toString()).header("Authorization", admin()))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verify(statistics).period(DAY, DAY); verifyNoMoreInteractions(statistics); cleared();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/admin/posts/stats/period/extra", "POST /api/v1/admin/posts/stats/today"})
    void given__an_unported_neighbor_or_method__when__admin_requests__then__keep_default_denial(String wire) throws Exception {
        var parts = wire.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", admin()))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        verifyNoInteractions(statistics); cleared();
    }

    private static String admin() { return "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"); }
    private static void cleared() { assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); }
}
