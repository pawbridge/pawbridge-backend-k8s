package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage;
import feign.Client;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeignPythonChatbotClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private final PythonChatbotMessageRequest input=new PythonChatbotMessageRequest(
            new AnimalChatbotContext("DOG","mix","2023년생","5(Kg)","white",null,null,"calm","보호중"),
            List.of(new ChatbotRecentMessage("user","입양 문의"),new ChatbotRecentMessage("assistant","저장된 답변")),"마스킹된 문의");

    @Test void given__explicit_transport_options_key_and_original_dtos__when__calling__then__send_exact_path_header_and_body_and_decode_three_response_fields() throws Exception {
        var captured=new AtomicReference<Request>();var observedOptions=new AtomicReference<Request.Options>();var options=new Request.Options();
        Client transport=(request,current)->{captured.set(request);observedOptions.set(current);return response(request,200,"{\"answer\":\"답변\",\"safetyNotice\":\"주의\",\"provider\":\"fixture\"}");};
        var result=new FeignPythonChatbotClient("http://python.example.invalid:8000",json,transport,options).createChatbotMessage("synthetic-internal-key",input);
        assertEquals("답변",result.answer());assertEquals("주의",result.safetyNotice());assertEquals("fixture",result.provider());
        var request=captured.get();assertEquals(Request.HttpMethod.POST,request.httpMethod());assertEquals("http://python.example.invalid:8000/internal/chatbot/messages",request.url());
        assertEquals(List.of("synthetic-internal-key"),List.copyOf(request.headers().get("X-Internal-Api-Key")));
        var body=json.readTree(request.body());assertEquals(3,body.size());assertEquals(9,body.get("animalContext").size());assertFalse(body.get("animalContext").has("animalId"));
        assertEquals(json.readTree(json.writeValueAsBytes(input)),body);assertEquals(2,body.get("recentMessages").get(0).size());assertSame(options,observedOptions.get());
    }

    @ParameterizedTest @ValueSource(ints={400,429,500})
    void given__upstream_http_failure__when__calling__then__propagate_feign_failure_without_translating_it_to_local_quota_or_retrying(int status) {
        var calls=new AtomicInteger();Client transport=(request,options)->{calls.incrementAndGet();return response(request,status,"private upstream detail");};
        var client=new FeignPythonChatbotClient("http://python.example.invalid",json,transport,new Request.Options());
        assertEquals(status,assertThrows(FeignException.class,()->client.createChatbotMessage("synthetic-key",input)).status());assertEquals(1,calls.get());
    }

    @Test void given__transport_io_failure__when__calling__then__never_retry_or_fabricate_an_answer() {
        var calls=new AtomicInteger();Client transport=(request,options)->{calls.incrementAndGet();throw new IOException("synthetic network failure");};
        var client=new FeignPythonChatbotClient("http://python.example.invalid",json,transport,new Request.Options());
        assertThrows(RetryableException.class,()->client.createChatbotMessage("synthetic-key",input));assertEquals(1,calls.get());
    }

    @Test void given__malformed_success_json__when__calling__then__propagate_decode_failure_without_an_answer() {
        Client transport=(request,options)->response(request,200,"{broken");
        var client=new FeignPythonChatbotClient("http://python.example.invalid",json,transport,new Request.Options());
        assertThrows(FeignException.class,()->client.createChatbotMessage("synthetic-key",input));
    }

    @Test void given__nullable_provider_response_fields__when__calling__then__preserve_original_unvalidated_values_for_persistence() {
        Client transport=(request,options)->response(request,200,"{\"answer\":null,\"safetyNotice\":null,\"provider\":null}");
        var result=new FeignPythonChatbotClient("http://python.example.invalid",json,transport,new Request.Options()).createChatbotMessage("synthetic-key",input);
        assertNull(result.answer());assertNull(result.safetyNotice());assertNull(result.provider());
    }

    private static Response response(Request request,int status,String body) {
        return Response.builder().request(request).status(status).reason("fixture").headers(Map.of("Content-Type",List.of("application/json")))
                .body(body,StandardCharsets.UTF_8).build();
    }
}
