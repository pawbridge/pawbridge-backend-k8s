package com.pawbridge.animalservice.dto.request;

import com.pawbridge.animalservice.chatbot.dto.AnimalChatbotContext;
import com.pawbridge.animalservice.chatbot.dto.ChatbotRecentMessage;
import java.util.List;

public record PythonChatbotMessageRequest(
        AnimalChatbotContext animalContext,
        List<ChatbotRecentMessage> recentMessages,
        String question
) {
}
