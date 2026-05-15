package com.pawbridge.animalservice.chatbot.dto;

public record ChatbotMessageResponse(
        String sessionId,
        String answer,
        String safetyNotice,
        String provider
) {
}
