package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChatbotMessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String apiValue;
}
