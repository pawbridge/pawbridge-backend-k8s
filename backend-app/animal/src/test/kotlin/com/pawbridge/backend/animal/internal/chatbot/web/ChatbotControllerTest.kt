package com.pawbridge.backend.animal.internal.chatbot.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.eqNonNull
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

class ChatbotControllerTest {
    private val service: ChatbotService = mock(ChatbotService::class.java)
    private val json = ObjectMapper()
    private val validator = LocalValidatorFactoryBean()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        validator.afterPropertiesSet()
        mvc = MockMvcBuilders.standaloneSetup(ChatbotController(service))
            .setControllerAdvice(ChatbotExceptionHandler())
            .setValidator(validator)
            .setMessageConverters(MappingJackson2HttpMessageConverter(json))
            .build()
    }

    @AfterEach
    fun close() {
        validator.close()
    }

    @Test
    fun given__anonymous_valid_body_and_non_uuid_session_text__when__posting__then__pass_original_request_and_return_bare_four_field_success() {
        val input = ChatbotMessageRequest("not-a-uuid", "입양 문의")
        val result = ChatbotMessageResponse("owned-session", "답변", "주의", "fixture")
        `when`(
            service.createMessage(
                eq(41L),
                eqNonNull(input),
                anyNonNull<HttpServletRequest>(),
                anyNonNull<HttpServletResponse>(),
            ),
        ).thenReturn(result)

        val body = json.readTree(
            mvc.perform(
                post("/api/v1/animals/41/chat/messages")
                    .header("X-User-Id", "99")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(input)),
            ).andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        assertEquals(json.readTree(json.writeValueAsBytes(result)), body)
        assertEquals(4, body.size())
        assertFalse(body.has("data"))
        assertFalse(body.has("code"))
        verify(service).createMessage(
            eq(41L),
            eqNonNull(input),
            anyNonNull<HttpServletRequest>(),
            anyNonNull<HttpServletResponse>(),
        )
        verifyNoMoreInteractions(service)
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun given__invalid_original_body_constraint__when__posting__then__validation_400_precedes_any_service_effect(
        request: ChatbotMessageRequest,
        message: String,
    ) {
        val body = mvc.perform(
            post("/api/v1/animals/41/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)),
        ).andExpect(status().isBadRequest()).andReturn().response.contentAsByteArray

        error(body, 400, message)
        verifyNoInteractions(service)
    }

    @Test
    fun given__malformed_json__when__posting__then__preserve_original_bad_body_message_without_service_call() {
        error(
            mvc.perform(
                post("/api/v1/animals/41/chat/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{broken"),
            ).andExpect(status().isBadRequest()).andReturn().response.contentAsByteArray,
            400,
            "요청 본문 형식이 올바르지 않습니다.",
        )
        verifyNoInteractions(service)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__original_business_or_upstream_or_storage_failure__when__posting__then__preserve_status_envelope_and_sanitized_server_messages(
        failure: RuntimeException,
        statusCode: Int,
        message: String,
    ) {
        `when`(
            service.createMessage(
                eq(41L),
                anyNonNull<ChatbotMessageRequest>(),
                anyNonNull<HttpServletRequest>(),
                anyNonNull<HttpServletResponse>(),
            ),
        ).thenThrow(failure)

        val body = mvc.perform(
            post("/api/v1/animals/41/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"입양 문의\"}"),
        ).andExpect(status().`is`(statusCode)).andReturn().response.contentAsByteArray

        error(body, statusCode, message)
        assertFalse(String(body, StandardCharsets.UTF_8).contains("private"))
    }

    @Test
    fun given__local_guard_result__when__posting__then__return_bare_200_instead_of_quota_429() {
        val result = ChatbotMessageResponse("owned-session", "보호동물 관련 질문을 입력해주세요.", "고정 주의문", "guard")
        `when`(
            service.createMessage(
                eq(41L),
                anyNonNull<ChatbotMessageRequest>(),
                anyNonNull<HttpServletRequest>(),
                anyNonNull<HttpServletResponse>(),
            ),
        ).thenReturn(result)

        val body = json.readTree(
            mvc.perform(
                post("/api/v1/animals/41/chat/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"주식 질문\"}"),
            ).andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        assertEquals(json.readTree(json.writeValueAsBytes(result)), body)
        assertEquals(4, body.size())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/animals/41/chat/messages",
            "/api/v1/animals/41/chat/message",
            "/api/v1/animals/41/chat/messages/admin",
        ],
    )
    fun given__noncanonical_route__when__posting__then__no_chatbot_controller_handler(path: String) {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"입양 문의\"}"))
            .andExpect(status().isNotFound())

        verifyNoInteractions(service)
    }

    private fun error(bytes: ByteArray, code: Int, message: String) {
        val body = json.readTree(bytes)
        assertEquals(3, body.size())
        assertEquals(code, body.get("code").intValue())
        assertEquals(message, body.get("message").asText())
        assertTrue(body.get("data").isNull())
    }

    companion object {
        @JvmStatic
        fun invalid(): Stream<Arguments> = Stream.of(
            Arguments.of(ChatbotMessageRequest(null, null), "question: question must not be blank"),
            Arguments.of(ChatbotMessageRequest(null, "   "), "question: question must not be blank"),
            Arguments.of(
                ChatbotMessageRequest(null, "가".repeat(501)),
                "question: question must be 500 characters or less",
            ),
            Arguments.of(
                ChatbotMessageRequest("s".repeat(37), "입양 문의"),
                "sessionId: sessionId must be 36 characters or less",
            ),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(ChatbotAnimalNotFoundException(), 404, "동물을 찾을 수 없습니다"),
            Arguments.of(IllegalArgumentException("Invalid chatbot session"), 400, "Invalid chatbot session"),
            Arguments.of(ChatbotRateLimitExceededException(), 429, "챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요."),
            Arguments.of(
                DataAccessResourceFailureException("private SQL and question"),
                500,
                "데이터베이스 오류가 발생했습니다.",
            ),
            Arguments.of(IllegalStateException("private upstream and key"), 500, "서버 내부 오류가 발생했습니다."),
        )
    }
}
