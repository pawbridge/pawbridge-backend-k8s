package com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception;

public final class ChatbotAnimalNotFoundException extends RuntimeException {
    public ChatbotAnimalNotFoundException() { super("동물을 찾을 수 없습니다"); }
}
