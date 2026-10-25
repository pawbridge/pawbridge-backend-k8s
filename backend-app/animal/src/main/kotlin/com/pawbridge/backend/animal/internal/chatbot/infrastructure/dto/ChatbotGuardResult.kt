package com.pawbridge.backend.animal.internal.chatbot.infrastructure.dto

@JvmRecord
data class ChatbotGuardResult(
    val blocked: Boolean,
    val questionForLlm: String?,
    val category: String?,
    val reason: String?,
    val userMessage: String?,
    val sensitive: Boolean,
) {

    companion object {
        @JvmStatic
        fun allowed(questionForLlm: String?): ChatbotGuardResult {
            return ChatbotGuardResult(false, questionForLlm, null, null, null, false)
        }

        @JvmStatic
        fun blocked(
            category: String?,
            reason: String?,
            userMessage: String?,
            sensitive: Boolean,
        ): ChatbotGuardResult {
            return ChatbotGuardResult(true, null, category, reason, userMessage, sensitive)
        }
    }
}
