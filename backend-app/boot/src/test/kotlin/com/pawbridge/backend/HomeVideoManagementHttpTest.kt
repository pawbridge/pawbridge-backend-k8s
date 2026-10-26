package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Board
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Order
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Publication
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Save
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler
import com.ninjasquad.springmockk.MockkBean
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

@WebMvcTest(
    controllers = [HomeVideoManagementController::class],
    properties = ["pawbridge.home-videos.management-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        HomeVideoManagementController::class,
        HomeVideoManagementExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class HomeVideoManagementHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var videos: HomeVideoManagement

    @ParameterizedTest
    @MethodSource("routes")
    fun `검증된 관리자와 위조된 역할 헤더일 때__각 관리 경로를 쓰면__원래 요청을 그대로 바인딩하고 기존 영상 형태로 응답한다`(
        method: String,
        suffix: String,
        body: String,
        operation: String,
    ) {
        // given
        val id = UUID.fromString(ID)
        val instant = Instant.parse("2030-01-01T00:00:00.123456Z")
        val board = Board(6, listOf(HomeVideo(id, "AbCdEfGhI_1", true, 0, instant, null, null, null, null, false, null)))
        val metadata = Metadata("AbCdEfGhI_1", "title", "channel", "https://i.ytimg.com/x.jpg", 60, true)

        every { videos.board() } returns board
        every { videos.preview(any()) } returns metadata
        every { videos.save(any(), any()) } returns board
        every { videos.publish(any(), any()) } returns board
        every { videos.reorder(any()) } returns board
        every { videos.recheck(any(), any()) } returns board

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(method), ROOT + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER")
                .header("X-User-Id", "999"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        if (operation == "preview") {
            result.andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"videoId\":\"AbCdEfGhI_1\",\"title\":\"title\",\"channelTitle\":\"channel\",\"thumbnailUrl\":\"https://i.ytimg.com/x.jpg\",\"durationSeconds\":60,\"available\":true}}",
                    JsonCompareMode.STRICT,
                ),
            )
        } else {
            result.andExpect(content().json(BOARD, JsonCompareMode.STRICT))
        }
        when (operation) {
            "board" -> verify(exactly = 1) { videos.board() }
            "preview" -> verify(exactly = 1) { videos.preview(URL) }
            "create" -> verify(exactly = 1) { videos.save(null, Save(URL, true, 5)) }
            "edit" -> verify(exactly = 1) { videos.save(id, Save(URL, true, 5)) }
            "publish" -> verify(exactly = 1) { videos.publish(id, Publication(false, 5)) }
            "order" -> verify(exactly = 1) { videos.reorder(Order(listOf(id), 5)) }
            "recheck" -> verify(exactly = 1) { videos.recheck(id, 5) }
        }
        confirmVerified(videos)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["malformed", "negative", "uuid"])
    fun `본문이나 리비전이나 ID가 잘못됐을 때__관리자가 바인딩하면__업무 호출 없이 원래의 400을 유지한다`(kind: String) {
        // given
        val call = if (kind == "uuid") put("$ROOT/bad-id") else post(ROOT)
        val body = if (kind == "malformed") {
            "{"
        } else {
            "{\"url\":\"" + URL + "\",\"revision\":" + (if (kind == "negative") "-1" else "5") + "}"
        }

        // when
        val result = mvc.perform(call.header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content(body))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"영상 주소와 요청 형식을 확인해 주세요.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(videos)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `원천이나 업무나 예기치 못한 실패가 났을 때__관리자가 읽으면__안전한 오류 형태를 그대로 유지한다`(
        failure: RuntimeException,
        code: Int,
        message: String,
    ) {
        // given
        every { videos.board() } throws failure

        // when
        val result = mvc.perform(get(ROOT).header("Authorization", admin()))

        // then
        result
            .andExpect(status().`is`(code))
            .andExpect(
                content().json(
                    "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    companion object {
        const val ROOT = "/api/v1/admin/videos"
        const val ID = "00000000-0000-0000-0000-000000000001"
        const val URL = "https://youtu.be/AbCdEfGhI_1"
        const val BOARD = """
        {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{"revision":6,"videos":[{
        "id":"00000000-0000-0000-0000-000000000001","videoId":"AbCdEfGhI_1","published":true,"position":0,
        "createdAt":"2030-01-01T00:00:00.123456Z","title":null,"channelTitle":null,"thumbnailUrl":null,"durationSeconds":null,"available":false,"checkedAt":null}]}}
        """

        @JvmStatic
        fun routes(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("GET", "", "", "board"),
                Arguments.of("POST", "/preview", "{\"url\":\"" + URL + "\"}", "preview"),
                Arguments.of("POST", "", "{\"url\":\"" + URL + "\",\"published\":true,\"revision\":5}", "create"),
                Arguments.of("PUT", "/" + ID, "{\"url\":\"" + URL + "\",\"published\":true,\"revision\":5}", "edit"),
                Arguments.of("PUT", "/" + ID + "/publication", "{\"published\":false,\"revision\":5}", "publish"),
                Arguments.of("PUT", "/order", "{\"ids\":[\"" + ID + "\"],\"revision\":5}", "order"),
                Arguments.of("POST", "/" + ID + "/recheck", "{\"published\":true,\"revision\":5}", "recheck"),
            )
        }

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(ResponseStatusException(HttpStatus.BAD_REQUEST, "고정400"), 400, "고정400"),
                Arguments.of(ResponseStatusException(HttpStatus.NOT_FOUND, "영상을 찾지 못했습니다."), 404, "영상을 찾지 못했습니다."),
                Arguments.of(
                    ResponseStatusException(HttpStatus.CONFLICT, "다른 관리자가 변경했습니다. 목록을 다시 불러와 주세요."),
                    409,
                    "다른 관리자가 변경했습니다. 목록을 다시 불러와 주세요.",
                ),
                Arguments.of(
                    ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    ),
                    503,
                    "YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                ),
                Arguments.of(
                    DataAccessResourceFailureException("synthetic-private-key SQL"),
                    503,
                    "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                ),
            )
        }

        fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
    }
}
