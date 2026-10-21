package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto;

public record ChatbotRecentMessage(
        String role,
        String content
) {
}
