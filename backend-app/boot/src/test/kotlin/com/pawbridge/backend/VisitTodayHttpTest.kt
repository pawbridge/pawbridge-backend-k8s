package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import jakarta.servlet.http.Cookie
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

    @MockkBean
    lateinit var visits: ReadTodayVisits

    @MockkBean
    lateinit var videos: ReadHomeVideos

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @MethodSource("publicReads")
    fun `익명 또는 잘못된 Bearer와 무시될 신원·쿠키가 주어졌을 때__오늘 방문을 조회하면__쿠키 없이 정확한 날짜와 정수 long을 돌려준다`(
        count: Long,
        authorization: String,
    ) {
        // given
        every { visits.read() } returns TodayVisits(LocalDate.of(2026, 10, 19), count)
        val request = get("/api/v1/visits/today")
            .header("X-User-Id", "999")
            .header("X-User-Role", "ROLE_ADMIN")
            .header("Origin", "https://outside.fixture.invalid")
            .header("User-Agent", "Fixture crawler")
            .cookie(Cookie("__Host-pb_visit", "ignored-synthetic-cookie"))
        if (authorization.isNotEmpty()) {
            request.header("Authorization", authorization)
        }

        // when
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

        // then
        val number = mapper.readTree(response.contentAsString).path("data").path("count")
        assertThat(number.isIntegralNumber).isTrue()
        assertThat(number.longValue()).isEqualTo(count)
        verify(exactly = 1) { visits.read() }
        confirmVerified(visits, videos, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `데이터 접근 실패가 주어졌을 때__오늘 방문을 조회하면__명시적 no-store와 안전한 503을 돌려준다`() {
        // given
        every { visits.read() } throws DataAccessResourceFailureException(
            "synthetic-private-query",
            SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"),
        )

        // when
        val result = mvc.perform(get("/api/v1/visits/today"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"방문 통계를 불러올 수 없습니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { visits.read() }
        confirmVerified(visits, videos, readPost, listPosts)
    }

    @ParameterizedTest
    @MethodSource("runtimeFailures")
    fun `DAO가 아닌 런타임 실패가 주어졌을 때__오늘 방문을 조회하면__500을 돌려주고 기존 보안 캐시 헤더를 따른다`(
        failure: RuntimeException,
    ) {
        // given
        every { visits.read() } throws failure
        every { readPost.read(any()) } throws IllegalStateException("synthetic-private-post")

        // when
        val visit = mvc.perform(get("/api/v1/visits/today"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
            .andReturn().response
        val post = mvc.perform(get("/api/v1/posts/read/42"))
            .andExpect(status().isInternalServerError())
            .andReturn().response

        // then
        // Both handlers leave cache policy to the same candidate security chain.
        assertThat(visit.getHeader("Cache-Control")).isNotNull().isEqualTo(post.getHeader("Cache-Control"))
        verify(exactly = 1) { visits.read() }
        verify(exactly = 1) { readPost.read(42) }
        confirmVerified(visits, videos, readPost, listPosts)
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
    fun `노출되지 않은 메서드나 인접 경로가 주어졌을 때__요청하면__방문 조회 전에 거부한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]))

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(visits, videos, readPost, listPosts)
    }

    @Test
    fun `보호된 즐겨찾기와 홈 런타임 실패가 주어졌을 때__요청하면__방문 매처와 어드바이스가 기존 계약을 유지한다`() {
        // given
        every { videos.read() } throws IllegalStateException("synthetic-private-home-video")

        // when
        val favorite = mvc.perform(get("/api/v1/favorites/42/check"))
        val home = mvc.perform(get("/api/v1/home/videos").header("Authorization", "Bearer invalid"))

        // then
        favorite
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json(
                    "{\"code\":401,\"message\":\"인증 토큰이 필요합니다.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        home
            .andExpect(status().isServiceUnavailable())
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { videos.read() }
        confirmVerified(visits, videos, readPost, listPosts)
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
