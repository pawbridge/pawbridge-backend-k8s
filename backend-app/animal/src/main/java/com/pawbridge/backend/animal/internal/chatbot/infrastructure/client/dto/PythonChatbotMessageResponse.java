package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto;

public record PythonChatbotMessageResponse(
        String answer,
        String safetyNotice,
        String provider
) {
}
