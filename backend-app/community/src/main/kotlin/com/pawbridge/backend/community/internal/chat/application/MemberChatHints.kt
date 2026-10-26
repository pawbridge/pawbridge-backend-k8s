package com.pawbridge.backend.community.internal.chat.application

/** Committed state hint only; implementations contain transport failures and never send message bodies. */
fun interface MemberChatHints {
    fun publish(signal: MemberChatModels.Signal)
}
