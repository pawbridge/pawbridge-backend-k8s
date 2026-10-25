package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits
import com.pawbridge.backend.community.internal.visit.application.TodayVisits
import com.pawbridge.backend.community.internal.visit.web.VisitTodayController
import com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
import java.sql.SQLException
import java.time.LocalDate
import java.util.stream.Stream

@WebMvcTest(
    controllers = [VisitTodayController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        VisitTodayController::class,
        VisitTodayExceptionHandler::class,
        HomeVideoController::class,
        HomeVideoExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class VisitTodayHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var visits: ReadTodayVisits

    @MockitoBean
    lateinit var videos: ReadHomeVideos

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @MethodSource("publicReads")
    fun given__anonymous_or_bad_bearer_with_ignored_identity_and_cookie__when__reading_today__then__return_exact_date_and_integral_long_without_cookie(
        count: Long,
        authorization: String,
    ) {
        whenever(visits.read()).thenReturn(TodayVisits(LocalDate.of(2026, 10, 19), count))
        val request = get("/api/v1/visits/today")
            .header("X-User-Id", "999")
            .header("X-User-Role", "ROLE_ADMIN")
            .header("Origin", "https://outside.fixture.invalid")
            .header("User-Agent", "Fixture crawler")
            .cookie(Cookie("__Host-pb_visit", "ignored-synthetic-cookie"))
        if (authorization.isNotEmpty()) {
            request.header("Authorization", authorization)
        }

        val response = mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"date\":\"2026-10-19\",\"count\":" + count + "}}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response

        val number = mapper.readTree(response.contentAsString).path("data").path("count")
        assertThat(number.isIntegralNumber).isTrue()
        assertThat(number.longValue()).isEqualTo(count)
        verify(visits).read()
        verifyNoInteractions(videos, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__data_access_failure__when__reading_today__then__return_safe_503_with_explicit_no_store() {
        whenever(visits.read()).thenThrow(
            DataAccessResourceFailureException(
                "synthetic-private-query",
                SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
            ),
        )

        mvc.perform(get("/api/v1/visits/today"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"방문 통계를 불러올 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )

        verify(visits).read()
        verifyNoInteractions(videos, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("runtimeFailures")
    fun given__non_dao_runtime_failure__when__reading_today__then__return_500_and_observe_the_existing_security_cache_headers(
        failure: RuntimeException,
    ) {
        whenever(visits.read()).thenThrow(failure)
        whenever(readPost.read(42)).thenThrow(IllegalStateException("synthetic-private-post"))

        val visit = mvc.perform(get("/api/v1/visits/today"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
            .andReturn().response
        val post = mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isInternalServerError())
            .andReturn().response

        // Both handlers leave cache policy to the same candidate security chain.
        assertThat(visit.getHeader("Cache-Control")).isNotNull().isEqualTo(post.getHeader("Cache-Control"))
        verify(visits).read()
        verify(readPost).read(42)
        verifyNoInteractions(videos, listPosts)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/visits", "GET /api/v1/visits/today/", "GET /api/v1/visits/today/extra",
            "GET /api/visits/today", "GET /api/v1/admin/visits/stats/period", "POST /api/v1/visits/identity",
            "POST /api/v1/visits/today", "PUT /api/v1/visits/today", "HEAD /api/v1/visits/today",
            "OPTIONS /api/v1/visits/today",
        ],
    )
    fun given__unexposed_method_or_adjacent_path__when__requesting__then__deny_before_visit_read(request: String) {
        val parts = request.split(" ", limit = 2)

        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])).andExpect(status().isForbidden())

        verifyNoInteractions(visits, videos, readPost, listPosts)
    }

    @Test
    fun given__protected_favorite_and_home_runtime_failure__when__requesting__then__visit_matcher_and_advice_preserve_existing_contracts() {
        whenever(videos.read()).thenThrow(IllegalStateException("synthetic-private-home-video"))

        mvc.perform(get("/api/v1/favorites/42/check"))
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json(
                    "{\"code\":401,\"message\":\"인증 토큰이 필요합니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(get("/api/v1/home/videos").header("Authorization", "Bearer invalid"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )

        verify(videos).read()
        verifyNoInteractions(visits, readPost, listPosts)
    }

    companion object {
        @JvmStatic
        fun publicReads(): Stream<Arguments> {
            return Stream.of(Arguments.of(0L, ""), Arguments.of(Long.MAX_VALUE, "Bearer invalid"))
        }

        @JvmStatic
        fun runtimeFailures(): Stream<RuntimeException> {
            return Stream.of(
                IllegalStateException("Bearer synthetic-private-token"),
                TransactionSystemException("synthetic-private-transaction", SQLException("synthetic-private-connection")),
            )
        }
    }
}
