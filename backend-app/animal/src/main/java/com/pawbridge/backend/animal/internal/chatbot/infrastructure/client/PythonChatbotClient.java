package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageResponse;
import feign.Headers;
import feign.Param;
import feign.RequestLine;

/** Only the original chatbot call; no Spring Cloud scan or unrelated AI endpoints. */
public interface PythonChatbotClient {
    @RequestLine("POST /internal/chatbot/messages")
    @Headers({"Content-Type: application/json", "X-Internal-Api-Key: {internalApiKey}"})
    PythonChatbotMessageResponse createChatbotMessage(@Param("internalApiKey") String internalApiKey,
                                                     PythonChatbotMessageRequest request);
}
