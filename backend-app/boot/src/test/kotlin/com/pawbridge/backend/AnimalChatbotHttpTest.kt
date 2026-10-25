package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.eqValue
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.animal-chatbot.enabled=true", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ChatbotController::class,
        ChatbotExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalChatbotHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var chatbot: ChatbotService

    @ParameterizedTest
    @ValueSource(strings = ["anonymous", "unrelated-invalid-bearer"])
    fun given__the_original_public_route__when__posting_without_a_member_identity__then__return_the_bare_chatbot_response(
        authorization: String,
    ) {
        val input = ChatbotMessageRequest("not-a-uuid", "입양 문의")
        whenever(
            chatbot.createMessage(
                eq(41L),
                eqValue(input),
                anyNonNull<HttpServletRequest>(),
                anyNonNull<HttpServletResponse>(),
            ),
        )
            .thenReturn(ChatbotMessageResponse("owned-session", "안내 답변", "안전 주의", "synthetic"))
        val command = command("POST", "/api/v1/animals/41/chat/messages")
            .header("X-User-Id", "99")
            .content(mapper.writeValueAsBytes(input))
        if (authorization == "unrelated-invalid-bearer") {
            command.header("Authorization", "Bearer synthetic-invalid-token")
        }
        val json = mapper.readTree(mvc.perform(command).andExpect(status().isOk()).andReturn().response.contentAsByteArray)
        assertThat(json.size()).isEqualTo(4)
        assertThat(json.path("sessionId").asText()).isEqualTo("owned-session")
        assertThat(json.path("answer").asText()).isEqualTo("안내 답변")
        assertThat(json.path("safetyNotice").asText()).isEqualTo("안전 주의")
        assertThat(json.path("provider").asText()).isEqualTo("synthetic")
        assertThat(json.has("data")).isFalse()
        assertThat(json.has("code")).isFalse()
        verify(chatbot).createMessage(
            eq(41L),
            eqValue(input),
            anyNonNull<HttpServletRequest>(),
            anyNonNull<HttpServletResponse>(),
        )
        verifyNoMoreInteractions(chatbot)
    }

    @Test
    fun given__an_invalid_original_question__when__posting_anonymously__then__return_the_scoped_validation_envelope_before_business() {
        val json = mapper.readTree(
            mvc.perform(command("POST", "/api/v1/animals/41/chat/messages").content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andReturn().response.contentAsByteArray,
        )
        assertThat(json.size()).isEqualTo(3)
        assertThat(json.path("code").asInt()).isEqualTo(400)
        assertThat(json.path("message").asText()).isEqualTo("question: question must not be blank")
        assertThat(json.get("data").isNull()).isTrue()
        verifyNoInteractions(chatbot)
    }

    @Test
    fun given__malformed_json__when__posting_anonymously__then__preserve_the_scoped_body_error_before_business() {
        val json = mapper.readTree(
            mvc.perform(command("POST", "/api/v1/animals/41/chat/messages").content("{broken"))
                .andExpect(status().isBadRequest())
                .andReturn().response.contentAsByteArray,
        )
        assertThat(json.path("code").asInt()).isEqualTo(400)
        assertThat(json.path("message").asText()).isEqualTo("요청 본문 형식이 올바르지 않습니다.")
        assertThat(json.get("data").isNull()).isTrue()
        verifyNoInteractions(chatbot)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/animals/41/chat/messages",
        "PUT,/api/v1/animals/41/chat/messages",
        "PATCH,/api/v1/animals/41/chat/messages",
        "DELETE,/api/v1/animals/41/chat/messages",
        "POST,/api/animals/41/chat/messages",
        "POST,/api/v1/animals/41/chat/message",
        "POST,/api/v1/animals/41/chat/messages/extra",
    )
    fun given__a_verified_member__when__using_a_noncanonical_method_or_alias__then__default_deny_before_chatbot(
        method: String,
        path: String,
    ) {
        mvc.perform(
            command(method, path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .content("{\"question\":\"입양 문의\"}"),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(chatbot)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
        }
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ChatbotController::class,
        ChatbotExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalChatbotDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var chatbot: ChatbotService

    @Test
    fun given__the_default_disabled_chatbot__when__posting_the_canonical_route__then__deny_before_business() {
        mvc.perform(
            AnimalChatbotHttpTest.command("POST", "/api/v1/animals/41/chat/messages")
                .content("{\"question\":\"입양 문의\"}"),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(chatbot)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-chatbot.enabled=on", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ChatbotController::class,
        ChatbotExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalChatbotAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var chatbot: ChatbotService

    @Test
    fun given__a_noncanonical_enable_flag__when__posting_the_canonical_route__then__deny_before_business() {
        mvc.perform(
            AnimalChatbotHttpTest.command("POST", "/api/v1/animals/41/chat/messages")
                .content("{\"question\":\"입양 문의\"}"),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(chatbot)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-chatbot.enabled=true", "pawbridge.animal-query.backend=http"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ChatbotController::class,
        ChatbotExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalChatbotOtherBackendHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var chatbot: ChatbotService

    @Test
    fun given__a_different_query_backend__when__posting_the_canonical_route__then__deny_before_business() {
        mvc.perform(
            AnimalChatbotHttpTest.command("POST", "/api/v1/animals/41/chat/messages")
                .content("{\"question\":\"입양 문의\"}"),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(chatbot)
    }
}
