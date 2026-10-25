package com.pawbridge.backend.animal.internal.chatbot.infrastructure.repository

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotMessage
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity.ChatbotSession
import org.springframework.data.jpa.repository.JpaRepository

interface ChatbotMessageRepository : JpaRepository<ChatbotMessage, Long> {

    fun findTop6BySessionOrderByCreatedAtDesc(session: ChatbotSession?): List<ChatbotMessage>

    fun countBySession(session: ChatbotSession?): Long
}
