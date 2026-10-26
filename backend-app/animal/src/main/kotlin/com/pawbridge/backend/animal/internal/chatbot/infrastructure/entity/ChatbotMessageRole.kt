package com.pawbridge.backend.animal.internal.chatbot.infrastructure.entity

enum class ChatbotMessageRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
}
