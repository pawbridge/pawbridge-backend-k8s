package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

@JvmRecord
data class AnimalChatbotContext(
    val species: String?,
    val breed: String?,
    val age: String?,
    val weight: String?,
    val color: String?,
    val gender: String?,
    val neutered: String?,
    val specialMark: String?,
    val processState: String?,
)
