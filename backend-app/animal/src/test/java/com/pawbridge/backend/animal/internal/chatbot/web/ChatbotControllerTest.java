package com.pawbridge.backend.animal.internal.chatbot.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotAnimalNotFoundException;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ChatbotControllerTest {
    private final ChatbotService service=mock(ChatbotService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final LocalValidatorFactoryBean validator=new LocalValidatorFactoryBean();
    private MockMvc mvc;
    @BeforeEach void setup() {
        validator.afterPropertiesSet();mvc=MockMvcBuilders.standaloneSetup(new ChatbotController(service))
                .setControllerAdvice(new ChatbotExceptionHandler()).setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @AfterEach void close(){validator.close();}

    @Test void given__anonymous_valid_body_and_non_uuid_session_text__when__posting__then__pass_original_request_and_return_bare_four_field_success() throws Exception {
        var input=new ChatbotMessageRequest("not-a-uuid","입양 문의");var result=new ChatbotMessageResponse("owned-session","답변","주의","fixture");
        when(service.createMessage(eq(41L),eq(input),any(),any())).thenReturn(result);
        var body=json.readTree(mvc.perform(post("/api/v1/animals/41/chat/messages").header("X-User-Id","99")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(json.readTree(json.writeValueAsBytes(result)),body);assertEquals(4,body.size());assertFalse(body.has("data"));assertFalse(body.has("code"));
        verify(service).createMessage(eq(41L),eq(input),any(),any());verifyNoMoreInteractions(service);
    }

    static Stream<Arguments> invalid(){return Stream.of(
            Arguments.of(new ChatbotMessageRequest(null,null),"question: question must not be blank"),
            Arguments.of(new ChatbotMessageRequest(null,"   "),"question: question must not be blank"),
            Arguments.of(new ChatbotMessageRequest(null,"가".repeat(501)),"question: question must be 500 characters or less"),
            Arguments.of(new ChatbotMessageRequest("s".repeat(37),"입양 문의"),"sessionId: sessionId must be 36 characters or less"));}
    @ParameterizedTest @MethodSource("invalid")
    void given__invalid_original_body_constraint__when__posting__then__validation_400_precedes_any_service_effect(ChatbotMessageRequest request,String message) throws Exception {
        byte[] body=mvc.perform(post("/api/v1/animals/41/chat/messages").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsByteArray();
        error(body,400,message);verifyNoInteractions(service);
    }

    @Test void given__malformed_json__when__posting__then__preserve_original_bad_body_message_without_service_call() throws Exception {
        error(mvc.perform(post("/api/v1/animals/41/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsByteArray(),400,"요청 본문 형식이 올바르지 않습니다.");
        verifyNoInteractions(service);
    }

    static Stream<Arguments> failures(){return Stream.of(
            Arguments.of(new ChatbotAnimalNotFoundException(),404,"동물을 찾을 수 없습니다"),
            Arguments.of(new IllegalArgumentException("Invalid chatbot session"),400,"Invalid chatbot session"),
            Arguments.of(new ChatbotRateLimitExceededException(),429,"챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요."),
            Arguments.of(new DataAccessResourceFailureException("private SQL and question"),500,"데이터베이스 오류가 발생했습니다."),
            Arguments.of(new IllegalStateException("private upstream and key"),500,"서버 내부 오류가 발생했습니다."));}
    @ParameterizedTest @MethodSource("failures")
    void given__original_business_or_upstream_or_storage_failure__when__posting__then__preserve_status_envelope_and_sanitized_server_messages(RuntimeException failure,int statusCode,String message) throws Exception {
        when(service.createMessage(eq(41L),any(),any(),any())).thenThrow(failure);
        byte[] body=mvc.perform(post("/api/v1/animals/41/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"입양 문의\"}"))
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsByteArray();
        error(body,statusCode,message);assertFalse(new String(body,java.nio.charset.StandardCharsets.UTF_8).contains("private"));
    }

    @Test void given__local_guard_result__when__posting__then__return_bare_200_instead_of_quota_429() throws Exception {
        var result=new ChatbotMessageResponse("owned-session","보호동물 관련 질문을 입력해주세요.","고정 주의문","guard");
        when(service.createMessage(eq(41L),any(),any(),any())).thenReturn(result);
        var body=json.readTree(mvc.perform(post("/api/v1/animals/41/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"주식 질문\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(json.readTree(json.writeValueAsBytes(result)),body);assertEquals(4,body.size());
    }

    @ParameterizedTest @ValueSource(strings={"/api/animals/41/chat/messages","/api/v1/animals/41/chat/message","/api/v1/animals/41/chat/messages/admin"})
    void given__noncanonical_route__when__posting__then__no_chatbot_controller_handler(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"입양 문의\"}")).andExpect(status().isNotFound());verifyNoInteractions(service);
    }

    private void error(byte[] bytes,int code,String message) throws Exception {
        var body=json.readTree(bytes);assertEquals(3,body.size());assertEquals(code,body.get("code").intValue());assertEquals(message,body.get("message").asText());assertTrue(body.get("data").isNull());
    }
}
