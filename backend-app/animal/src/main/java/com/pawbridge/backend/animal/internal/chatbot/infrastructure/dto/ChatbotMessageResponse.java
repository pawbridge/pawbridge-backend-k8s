package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto;

public record ChatbotMessageResponse(
        String sessionId,
        String answer,
        String safetyNotice,
        String provider
) {
}
