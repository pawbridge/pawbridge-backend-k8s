package com.pawbridge.backend.community.internal.chat.web

import java.nio.charset.StandardCharsets
import org.springframework.messaging.Message
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler

/** Never echo an untrusted CONNECT/SEND frame, parser error or exception into the response. */
open class MemberChatProtocolErrors : StompSubProtocolErrorHandler() {

    override fun handleClientMessageProcessingError(
        clientMessage: Message<ByteArray>?,
        ex: Throwable,
    ): Message<ByteArray> = rejected()

    override fun handleErrorMessageToClient(errorMessage: Message<ByteArray>): Message<ByteArray> = rejected()

    private fun rejected(): Message<ByteArray> {
        val headers = StompHeaderAccessor.create(StompCommand.ERROR)
        headers.message = "채팅 연결을 다시 시작하세요."
        // Match Spring's mutable error-frame contract without including the submitted frame.
        headers.setLeaveMutable(true)
        return MessageBuilder.createMessage(
            "채팅 요청을 처리하지 못했습니다.".toByteArray(StandardCharsets.UTF_8),
            headers.messageHeaders,
        )
    }
}
