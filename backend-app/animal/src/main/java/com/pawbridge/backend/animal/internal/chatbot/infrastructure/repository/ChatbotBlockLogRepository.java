package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotBlockLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatbotBlockLogRepository extends JpaRepository<ChatbotBlockLog, Long> {
}
