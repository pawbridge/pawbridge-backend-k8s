package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotSession
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ChatbotSessionRepository : JpaRepository<ChatbotSession, String> {

    fun findByIdAndAnonymousSessionIdAndAnimal_Id(
        id: String?,
        anonymousSessionId: String?,
        animalId: Long?,
    ): Optional<ChatbotSession>
}
