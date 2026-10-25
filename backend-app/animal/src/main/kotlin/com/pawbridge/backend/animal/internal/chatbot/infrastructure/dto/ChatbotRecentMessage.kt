package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

@JvmRecord
data class ChatbotRecentMessage(
    val role: String?,
    val content: String?,
)
