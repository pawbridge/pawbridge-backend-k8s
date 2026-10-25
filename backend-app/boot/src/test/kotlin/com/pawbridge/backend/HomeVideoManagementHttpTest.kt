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
import com.pawbridge.backend.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
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
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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

    @MockitoBean
    lateinit var videos: HomeVideoManagement

    @ParameterizedTest
    @MethodSource("routes")
    fun given__a_verified_admin_and_spoofed_role__when__using_each_management_route__then__bind_exact_source_requests_and_existing_video_shape(
        method: String,
        suffix: String,
        body: String,
        operation: String,
    ) {
        val id = UUID.fromString(ID)
        val instant = Instant.parse("2030-01-01T00:00:00.123456Z")
        val board = Board(6, listOf(HomeVideo(id, "AbCdEfGhI_1", true, 0, instant, null, null, null, null, false, null)))
        val metadata = Metadata("AbCdEfGhI_1", "title", "channel", "https://i.ytimg.com/x.jpg", 60, true)
        whenever(videos.board()).thenReturn(board)
        whenever(videos.preview(anyString())).thenReturn(metadata)
        whenever(videos.save(any<UUID>(), anyNonNull<Save>())).thenReturn(board)
        whenever(videos.publish(anyNonNull<UUID>(), anyNonNull<Publication>())).thenReturn(board)
        whenever(videos.reorder(anyNonNull<Order>())).thenReturn(board)
        whenever(videos.recheck(anyNonNull<UUID>(), anyLong())).thenReturn(board)
        val result = mvc.perform(
            request(HttpMethod.valueOf(method), ROOT + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", admin())
                .header("X-User-Role", "ROLE_USER")
                .header("X-User-Id", "999"),
        )
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
            "board" -> verify(videos).board()
            "preview" -> verify(videos).preview(URL)
            "create" -> verify(videos).save(null, Save(URL, true, 5))
            "edit" -> verify(videos).save(id, Save(URL, true, 5))
            "publish" -> verify(videos).publish(id, Publication(false, 5))
            "order" -> verify(videos).reorder(Order(listOf(id), 5))
            "recheck" -> verify(videos).recheck(id, 5)
        }
        verifyNoMoreInteractions(videos)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["malformed", "negative", "uuid"])
    fun given__invalid_body_revision_or_id__when__admin_binds__then__retain_source_400_without_business(kind: String) {
        val call = if (kind == "uuid") put("$ROOT/bad-id") else post(ROOT)
        val body = if (kind == "malformed") {
            "{"
        } else {
            "{\"url\":\"" + URL + "\",\"revision\":" + (if (kind == "negative") "-1" else "5") + "}"
        }
        mvc.perform(call.header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(
                content().json(
                    "{\"code\":400,\"message\":\"영상 주소와 요청 형식을 확인해 주세요.\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
        verifyNoInteractions(videos)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "user"])
    fun given__unverified_or_nonadmin_identity__when__posting_invalid_body_with_admin_header__then__reject_before_binding(
        mode: String,
    ) {
        val call = post("$ROOT/preview")
            .header("X-User-Role", "ROLE_ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{")
        if (mode == "user") {
            call.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
        mvc.perform(call).andExpect(status().`is`(if (mode == "missing") 401 else 403))
        verifyNoInteractions(videos)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__source_business_or_unexpected_failure__when__an_admin_reads__then__retain_exact_safe_error_family(
        failure: RuntimeException,
        code: Int,
        message: String,
    ) {
        whenever(videos.board()).thenThrow(failure)
        mvc.perform(get(ROOT).header("Authorization", admin()))
            .andExpect(status().`is`(code))
            .andExpect(
                content().json(
                    "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @Test
    fun given__unported_method_or_deeper_path__when__admin_requests__then__keep_default_deny() {
        mvc.perform(delete("$ROOT/$ID").header("Authorization", admin())).andExpect(status().isForbidden())
        mvc.perform(post("$ROOT/$ID/recheck/extra").header("Authorization", admin())).andExpect(status().isForbidden())
        verifyNoInteractions(videos)
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
