package com.pawbridge.backend.animal.internal.chatbot.infrastructure.client.dto

@JvmRecord
data class PythonChatbotMessageResponse(
    val answer: String?,
    val safetyNotice: String?,
    val provider: String?,
)
