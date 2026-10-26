package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// Java record는 컴포넌트 annotation을 생성자 인자·필드·접근자 세 곳에 붙이므로 같은 위치에 붙인다.
@JvmRecord
data class ChatbotMessageRequest(
    @param:Size(max = 36, message = "sessionId must be 36 characters or less")
    @field:Size(max = 36, message = "sessionId must be 36 characters or less")
    @get:Size(max = 36, message = "sessionId must be 36 characters or less")
    val sessionId: String?,
    @param:NotBlank(message = "question must not be blank")
    @param:Size(max = 500, message = "question must be 500 characters or less")
    @field:NotBlank(message = "question must not be blank")
    @field:Size(max = 500, message = "question must be 500 characters or less")
    @get:NotBlank(message = "question must not be blank")
    @get:Size(max = 500, message = "question must be 500 characters or less")
    val question: String?,
)
