package com.pawbridge.animalservice.chatbot.dto;

public record ChatbotGuardResult(
        boolean blocked,
        String questionForLlm,
        String category,
        String reason,
        String userMessage,
        boolean sensitive
) {

    public static ChatbotGuardResult allowed(String questionForLlm) {
        return new ChatbotGuardResult(false, questionForLlm, null, null, null, false);
    }

    public static ChatbotGuardResult blocked(
            String category,
            String reason,
            String userMessage,
            boolean sensitive
    ) {
        return new ChatbotGuardResult(true, null, category, reason, userMessage, sensitive);
    }
}
