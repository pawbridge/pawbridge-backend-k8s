package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.ninjasquad.springmockk.MockkBean
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

@WebMvcTest(
    controllers = [HomeVideoController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        HomeVideoController::class,
        HomeVideoExceptionHandler::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class HomeVideoHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var videos: ReadHomeVideos

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(strings = ["", "Bearer invalid"])
    fun `익명이거나 잘못된 베어러일 때__홈 영상을 읽으면__열한 개 필드와 null과 마이크로초 시각을 그대로 돌려준다`(
        authorization: String,
    ) {
        // given
        val request = get("/api/v1/home/videos").header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
        if (authorization.isNotEmpty()) {
            request.header("Authorization", authorization)
        }

        every { videos.read() } returns listOf(
            HomeVideo(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "abcdefghijk",
                true,
                -1,
                Instant.parse("2026-10-01T03:04:05.123456Z"),
                null,
                "",
                null,
                null,
                true,
                Instant.parse("2026-10-19T05:06:07.654321Z"),
            ),
            HomeVideo(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "lmnopqrstuv",
                true,
                0,
                Instant.parse("2026-10-02T03:04:05.000001Z"),
                "",
                "기관",
                "https://images.fixture.invalid/video.jpg",
                2_147_483_648L,
                true,
                Instant.parse("2026-10-19T05:06:08.000001Z"),
            ),
        )

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    """
                    {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":[
                      {"id":"00000000-0000-0000-0000-000000000001","videoId":"abcdefghijk","published":true,"position":-1,
                       "createdAt":"2026-10-01T03:04:05.123456Z","title":null,"channelTitle":"","thumbnailUrl":null,
                       "durationSeconds":null,"available":true,"checkedAt":"2026-10-19T05:06:07.654321Z"},
                      {"id":"00000000-0000-0000-0000-000000000002","videoId":"lmnopqrstuv","published":true,"position":0,
                       "createdAt":"2026-10-02T03:04:05.000001Z","title":"","channelTitle":"기관",
                       "thumbnailUrl":"https://images.fixture.invalid/video.jpg","durationSeconds":2147483648,
                       "available":true,"checkedAt":"2026-10-19T05:06:08.000001Z"}
                    ]}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { videos.read() }
        confirmVerified(videos, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `보이는 영상이 없을 때__홈을 읽으면__성공 응답과 빈 배열을 돌려준다`() {
        // given
        every { videos.read() } returns listOf()

        // when
        val result = mvc.perform(get("/api/v1/home/videos"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":[]}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { videos.read() }
    }

    @ParameterizedTest
    @MethodSource("readFailures")
    fun `조회나 트랜잭션이나 런타임 실패가 났을 때__홈을 읽으면__안전한 503 응답만 돌려준다`(
        failure: RuntimeException,
    ) {
        // given
        every { videos.read() } throws failure

        // when
        val result = mvc.perform(get("/api/v1/home/videos").header("Authorization", "Bearer synthetic-private-token"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":503,\"message\":\"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { videos.read() }
        confirmVerified(videos, readPost, listPosts)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/home", "GET /api/v1/home/videos/", "GET /api/v1/home/videos/extra",
            "GET /api/home/videos", "GET /api/v1/admin/videos", "POST /api/v1/home/videos",
            "PUT /api/v1/home/videos", "DELETE /api/v1/home/videos", "HEAD /api/v1/home/videos",
            "OPTIONS /api/v1/home/videos",
        ],
    )
    fun `노출하지 않은 메서드나 인접 경로일 때__요청하면__홈 영상 조회 전에 거절한다`(request: String) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]))

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(videos, readPost, listPosts)
    }

    @Test
    fun `보호된 즐겨찾기 조회와 게시글 읽기 실패일 때__요청하면__홈 영상 어드바이스와 공개 매처의 범위가 유지된다`() {
        // given
        every { readPost.read(any()) } throws IllegalStateException("synthetic-private-post")

        // when
        val favorite = mvc.perform(get("/api/v1/favorites/42/check"))
        val post = mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))

        // then
        favorite
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().json("{\"code\":401,\"message\":\"인증 토큰이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        post
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { readPost.read(42) }
        confirmVerified(videos, readPost, listPosts)
    }

    companion object {
        @JvmStatic
        fun readFailures(): Stream<RuntimeException> {
            val cause = SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password")
            return Stream.of(
                DataAccessResourceFailureException("synthetic-private-query", cause),
                TransactionSystemException("synthetic-private-transaction", cause),
                IllegalStateException("synthetic-private-runtime", cause),
            )
        }
    }
}
