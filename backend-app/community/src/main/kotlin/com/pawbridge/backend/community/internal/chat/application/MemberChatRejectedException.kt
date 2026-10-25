package com.pawbridge.backend.community.internal.chat.application

class MemberChatRejectedException(
    private val status: Int,
    message: String?,
) : RuntimeException(message) {

    fun status(): Int = status
}
