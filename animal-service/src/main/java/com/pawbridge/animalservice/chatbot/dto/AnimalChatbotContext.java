package com.pawbridge.animalservice.chatbot.dto;

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
