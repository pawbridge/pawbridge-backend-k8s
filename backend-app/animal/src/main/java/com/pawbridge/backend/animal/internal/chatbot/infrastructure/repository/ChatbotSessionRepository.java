package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository;

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatbotSessionRepository extends JpaRepository<ChatbotSession, String> {

    Optional<ChatbotSession> findByIdAndAnonymousSessionIdAndAnimal_Id(
            String id,
            String anonymousSessionId,
            Long animalId
    );
}
