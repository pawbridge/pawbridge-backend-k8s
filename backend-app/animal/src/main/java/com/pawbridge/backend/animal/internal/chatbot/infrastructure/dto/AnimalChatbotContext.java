package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto;

public record AnimalChatbotContext(
        String species,
        String breed,
        String age,
        String weight,
        String color,
        String gender,
        String neutered,
        String specialMark,
        String processState
) {
}
