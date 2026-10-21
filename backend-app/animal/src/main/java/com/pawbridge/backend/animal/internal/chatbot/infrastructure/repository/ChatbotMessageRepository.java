package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotMessage;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatbotMessageRepository extends JpaRepository<ChatbotMessage, Long> {

    List<ChatbotMessage> findTop6BySessionOrderByCreatedAtDesc(ChatbotSession session);

    long countBySession(ChatbotSession session);
}
