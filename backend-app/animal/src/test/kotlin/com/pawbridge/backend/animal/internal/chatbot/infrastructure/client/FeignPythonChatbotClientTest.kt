package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage
import feign.Client
import feign.FeignException
import feign.Request
import feign.Response
import feign.RetryableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FeignPythonChatbotClientTest {
    private val json = ObjectMapper()
    private val input = PythonChatbotMessageRequest(
        AnimalChatbotContext("DOG", "mix", "2023년생", "5(Kg)", "white", null, null, "calm", "보호중"),
        listOf(ChatbotRecentMessage("user", "입양 문의"), ChatbotRecentMessage("assistant", "저장된 답변")),
        "마스킹된 문의",
    )

    @Test
    fun given__explicit_transport_options_key_and_original_dtos__when__calling__then__send_exact_path_header_and_body_and_decode_three_response_fields() {
        val captured = AtomicReference<Request>()
        val observedOptions = AtomicReference<Request.Options>()
        val options = Request.Options()
        val transport = Client { request, current ->
            captured.set(request)
            observedOptions.set(current)
            response(request, 200, "{\"answer\":\"답변\",\"safetyNotice\":\"주의\",\"provider\":\"fixture\"}")
        }

        val result = FeignPythonChatbotClient("http://python.example.invalid:8000", json, transport, options)
            .createChatbotMessage("synthetic-internal-key", input)!!

        assertEquals("답변", result.answer)
        assertEquals("주의", result.safetyNotice)
        assertEquals("fixture", result.provider)
        val request = captured.get()
        assertEquals(Request.HttpMethod.POST, request.httpMethod())
        assertEquals("http://python.example.invalid:8000/internal/chatbot/messages", request.url())
        assertEquals(listOf("synthetic-internal-key"), request.headers()["X-Internal-Api-Key"]!!.toList())
        val body = json.readTree(request.body())
        assertEquals(3, body.size())
        assertEquals(9, body.get("animalContext").size())
        assertFalse(body.get("animalContext").has("animalId"))
        assertEquals(json.readTree(json.writeValueAsBytes(input)), body)
        assertEquals(2, body.get("recentMessages").get(0).size())
        assertSame(options, observedOptions.get())
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 429, 500])
    fun given__upstream_http_failure__when__calling__then__propagate_feign_failure_without_translating_it_to_local_quota_or_retrying(
        status: Int,
    ) {
        val calls = AtomicInteger()
        val transport = Client { request, _ ->
            calls.incrementAndGet()
            response(request, status, "private upstream detail")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        assertEquals(
            status,
            assertThrows(FeignException::class.java) { client.createChatbotMessage("synthetic-key", input) }.status(),
        )
        assertEquals(1, calls.get())
    }

    @Test
    fun given__transport_io_failure__when__calling__then__never_retry_or_fabricate_an_answer() {
        val calls = AtomicInteger()
        val transport = Client { _, _ ->
            calls.incrementAndGet()
            throw IOException("synthetic network failure")
        }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        assertThrows(RetryableException::class.java) { client.createChatbotMessage("synthetic-key", input) }
        assertEquals(1, calls.get())
    }

    @Test
    fun given__malformed_success_json__when__calling__then__propagate_decode_failure_without_an_answer() {
        val transport = Client { request, _ -> response(request, 200, "{broken") }
        val client = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())

        assertThrows(FeignException::class.java) { client.createChatbotMessage("synthetic-key", input) }
    }

    @Test
    fun given__nullable_provider_response_fields__when__calling__then__preserve_original_unvalidated_values_for_persistence() {
        val transport = Client { request, _ ->
            response(request, 200, "{\"answer\":null,\"safetyNotice\":null,\"provider\":null}")
        }

        val result = FeignPythonChatbotClient("http://python.example.invalid", json, transport, Request.Options())
            .createChatbotMessage("synthetic-key", input)!!

        assertNull(result.answer)
        assertNull(result.safetyNotice)
        assertNull(result.provider)
    }

    private fun response(request: Request, status: Int, body: String): Response {
        return Response.builder()
            .request(request)
            .status(status)
            .reason("fixture")
            .headers(mapOf("Content-Type" to listOf("application/json")))
            .body(body, StandardCharsets.UTF_8)
            .build()
    }
}
