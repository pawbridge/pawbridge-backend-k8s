package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageRequest
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto.PythonChatbotMessageResponse
import feign.Headers
import feign.Param
import feign.RequestLine

/** Only the original chatbot call; no Spring Cloud scan or unrelated AI endpoints. */
interface PythonChatbotClient {
    @RequestLine("POST /internal/chatbot/messages")
    @Headers("Content-Type: application/json", "X-Internal-Api-Key: {internalApiKey}")
    fun createChatbotMessage(
        @Param("internalApiKey") internalApiKey: String?,
        request: PythonChatbotMessageRequest?,
    ): PythonChatbotMessageResponse?
}
