package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.AnimalChatbotContext;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto.ChatbotRecentMessage;
import java.util.List;

public record PythonChatbotMessageRequest(
        AnimalChatbotContext animalContext,
        List<ChatbotRecentMessage> recentMessages,
        String question
) {
}
