package com.pawbridge.animalservice.chatbot.exception;

public class ChatbotRateLimitExceededException extends RuntimeException {

    public ChatbotRateLimitExceededException() {
        super("챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요.");
    }
}
