package com.pawbridge.animalservice.chatbot.repository;

import com.pawbridge.animalservice.chatbot.entity.ChatbotBlockLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatbotBlockLogRepository extends JpaRepository<ChatbotBlockLog, Long> {
}
