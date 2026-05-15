package com.pawbridge.animalservice.chatbot.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatbotMessageRequest(
        String sessionId,
        @NotBlank(message = "question must not be blank")
        String question
) {
}
