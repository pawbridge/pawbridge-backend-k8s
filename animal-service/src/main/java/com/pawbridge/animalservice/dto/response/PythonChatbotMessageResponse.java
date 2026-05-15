package com.pawbridge.animalservice.dto.response;

public record PythonChatbotMessageResponse(
        String answer,
        String safetyNotice,
        String provider
) {
}
