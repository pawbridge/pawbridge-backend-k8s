package com.pawbridge.animalservice.chatbot.repository;

import com.pawbridge.animalservice.chatbot.entity.ChatbotMessage;
import com.pawbridge.animalservice.chatbot.entity.ChatbotSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatbotMessageRepository extends JpaRepository<ChatbotMessage, Long> {

    List<ChatbotMessage> findTop6BySessionOrderByCreatedAtDesc(ChatbotSession session);
}
