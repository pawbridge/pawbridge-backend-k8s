package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageRequest;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotMessageResponse;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.service.ChatbotService;
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotController;
import com.pawbridge.backend.animal.internal.chatbot.web.ChatbotExceptionHandler;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(properties={"pawbridge.animal-chatbot.enabled=true","pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ChatbotController.class,ChatbotExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalChatbotHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChatbotService chatbot;

    @ParameterizedTest
    @ValueSource(strings={"anonymous","unrelated-invalid-bearer"})
    void given__the_original_public_route__when__posting_without_a_member_identity__then__return_the_bare_chatbot_response(String authorization) throws Exception {
        var input=new ChatbotMessageRequest("not-a-uuid","입양 문의");
        when(chatbot.createMessage(eq(41L),eq(input),any(),any()))
                .thenReturn(new ChatbotMessageResponse("owned-session","안내 답변","안전 주의","synthetic"));
        var command=command("POST","/api/v1/animals/41/chat/messages")
                .header("X-User-Id","99").content(mapper.writeValueAsBytes(input));
        if(authorization.equals("unrelated-invalid-bearer"))command.header("Authorization","Bearer synthetic-invalid-token");
        var json=mapper.readTree(mvc.perform(command).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.path("sessionId").asText()).isEqualTo("owned-session");
        assertThat(json.path("answer").asText()).isEqualTo("안내 답변");
        assertThat(json.path("safetyNotice").asText()).isEqualTo("안전 주의");
        assertThat(json.path("provider").asText()).isEqualTo("synthetic");
        assertThat(json.has("data")).isFalse();
        assertThat(json.has("code")).isFalse();
        verify(chatbot).createMessage(eq(41L),eq(input),any(),any());
        verifyNoMoreInteractions(chatbot);
    }

    @Test
    void given__an_invalid_original_question__when__posting_anonymously__then__return_the_scoped_validation_envelope_before_business() throws Exception {
        var json=mapper.readTree(mvc.perform(command("POST","/api/v1/animals/41/chat/messages")
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.path("code").asInt()).isEqualTo(400);
        assertThat(json.path("message").asText()).isEqualTo("question: question must not be blank");
        assertThat(json.get("data").isNull()).isTrue();
        verifyNoInteractions(chatbot);
    }

    @Test
    void given__malformed_json__when__posting_anonymously__then__preserve_the_scoped_body_error_before_business() throws Exception {
        var json=mapper.readTree(mvc.perform(command("POST","/api/v1/animals/41/chat/messages").content("{broken"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.path("code").asInt()).isEqualTo(400);
        assertThat(json.path("message").asText()).isEqualTo("요청 본문 형식이 올바르지 않습니다.");
        assertThat(json.get("data").isNull()).isTrue();
        verifyNoInteractions(chatbot);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/animals/41/chat/messages","PUT,/api/v1/animals/41/chat/messages",
            "PATCH,/api/v1/animals/41/chat/messages","DELETE,/api/v1/animals/41/chat/messages",
            "POST,/api/animals/41/chat/messages","POST,/api/v1/animals/41/chat/message",
            "POST,/api/v1/animals/41/chat/messages/extra"})
    void given__a_verified_member__when__using_a_noncanonical_method_or_alias__then__default_deny_before_chatbot(String method,String path) throws Exception {
        mvc.perform(command(method,path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"))
                .content("{\"question\":\"입양 문의\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(chatbot);
    }

    static MockHttpServletRequestBuilder command(String method,String path) {
        return request(HttpMethod.valueOf(method),path).contentType(MediaType.APPLICATION_JSON);
    }
}

@WebMvcTest(properties="pawbridge.animal-query.backend=postgresql",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ChatbotController.class,ChatbotExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalChatbotDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChatbotService chatbot;

    @Test
    void given__the_default_disabled_chatbot__when__posting_the_canonical_route__then__deny_before_business() throws Exception {
        mvc.perform(AnimalChatbotHttpTest.command("POST","/api/v1/animals/41/chat/messages")
                .content("{\"question\":\"입양 문의\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(chatbot);
    }
}

@WebMvcTest(properties={"pawbridge.animal-chatbot.enabled=on","pawbridge.animal-query.backend=postgresql"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ChatbotController.class,ChatbotExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalChatbotAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChatbotService chatbot;

    @Test
    void given__a_noncanonical_enable_flag__when__posting_the_canonical_route__then__deny_before_business() throws Exception {
        mvc.perform(AnimalChatbotHttpTest.command("POST","/api/v1/animals/41/chat/messages")
                .content("{\"question\":\"입양 문의\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(chatbot);
    }
}

@WebMvcTest(properties={"pawbridge.animal-chatbot.enabled=true","pawbridge.animal-query.backend=http"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ChatbotController.class,ChatbotExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalChatbotOtherBackendHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChatbotService chatbot;

    @Test
    void given__a_different_query_backend__when__posting_the_canonical_route__then__deny_before_business() throws Exception {
        mvc.perform(AnimalChatbotHttpTest.command("POST","/api/v1/animals/41/chat/messages")
                .content("{\"question\":\"입양 문의\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(chatbot);
    }
}
