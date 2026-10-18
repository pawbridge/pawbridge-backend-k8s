package com.pawbridge.animalservice.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatbotMessageRequest(
        @Size(max = 36, message = "sessionId must be 36 characters or less")
        String sessionId,
        @NotBlank(message = "question must not be blank")
        @Size(max = 500, message = "question must be 500 characters or less")
        String question
) {
}
