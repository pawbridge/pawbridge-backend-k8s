package com.pawbridge.backend.community.internal.chat.web

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Failure
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import java.security.Principal
import java.time.Clock
import org.springframework.http.HttpStatus
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.stereotype.Controller

@Controller
class MemberChatSocketController(
    private val service: MemberChat,
    private val messaging: SimpMessagingTemplate,
    private val clock: Clock,
) {

    // `input` stays non-null: Spring requires the payload (`@Payload` is required by default), so an empty
    // payload fails in the argument resolver and reaches invalidFrame() exactly as before.
    @MessageMapping("/chat/send")
    fun send(
        @Payload input: Send,
        principal: Principal?,
        @Header("simpSessionId") sessionId: String,
    ) {
        if (principal !is MemberChatTickets.Identity) {
            return
        }
        val response: Any = try {
            if (principal.expiresAt <= clock.millis()) {
                throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "로그인이 만료되었습니다.")
            }
            service.send(principal.memberId, input)
        } catch (rejected: MemberChatRejectedException) {
            Failure(input.requestId, rejected.status(), rejected.message)
        } catch (unavailable: RuntimeException) {
            Failure(input.requestId, 503, "전송 결과를 확인하지 못했습니다. 같은 메시지로 다시 시도하세요.")
        }
        // The application receipt belongs to the sending connection, not every tab of this member.
        val headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE)
        headers.sessionId = sessionId
        headers.setLeaveMutable(true)
        messaging.convertAndSendToUser(sessionId, "/queue/chat", response, headers.messageHeaders)
    }

    @MessageExceptionHandler(Exception::class)
    @SendToUser(value = ["/queue/chat"], broadcast = false)
    fun invalidFrame(): Failure {
        // Message conversion errors may contain the submitted body; suppress the exception entirely.
        return Failure(null, 400, "메시지 형식을 확인해 주세요.")
    }
}
