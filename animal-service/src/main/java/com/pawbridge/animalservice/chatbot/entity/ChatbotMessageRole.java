package com.pawbridge.animalservice.chatbot.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatbotMessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String apiValue;
}
