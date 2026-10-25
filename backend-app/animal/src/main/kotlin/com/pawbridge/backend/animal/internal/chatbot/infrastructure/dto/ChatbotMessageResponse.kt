package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

@JvmRecord
data class ChatbotMessageResponse(
    val sessionId: String?,
    val answer: String?,
    val safetyNotice: String?,
    val provider: String?,
)
