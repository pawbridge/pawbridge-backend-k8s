package com.pawbridge.backend.community.internal.chat.web

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Failure
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.springframework.context.support.StaticApplicationContext
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler
import org.springframework.messaging.support.ExecutorSubscribableChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.util.MimeTypeUtils
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

/** Dispatches /app/chat/send through Spring's real annotation handler, so argument resolution is covered. */
class MemberChatSocketDispatchTest {
    private val outbound = ArrayList<Message<*>>()
    private val chat = mock(MemberChat::class.java)
    private val handler = handler()

    @Test
    fun givenTicketIdentityAndValidFrame__whenSending__thenServiceReceivesItAndReceiptGoesToTheSession() {
        val receipt = Receipt(REQUEST, UUID.randomUUID(), 1)
        whenever(chat.send(anyLong(), anyNonNull())).thenReturn(receipt)

        dispatch(VALID_FRAME, MemberChatTickets.Identity(7, Long.MAX_VALUE))

        verify(chat).send(7, Send(8L, REQUEST, "hi", null, null))
        assertThat(outbound).singleElement().satisfies({ message ->
            assertThat(SimpMessageHeaderAccessor.getDestination(message.headers)).isEqualTo("/user/s1/queue/chat")
            assertThat(message.payload).isEqualTo(receipt)
        })
    }

    @Test
    fun givenEmptyPayload__whenSending__thenNothingIsSentAndInvalidFrameAnswers() {
        dispatch("", MemberChatTickets.Identity(7, Long.MAX_VALUE))

        verify(chat, never()).send(anyLong(), anyNonNull())
        assertThat(outbound).singleElement().satisfies({ message ->
            assertThat(message.payload).isEqualTo(Failure(null, 400, "메시지 형식을 확인해 주세요."))
        })
    }

    @Test
    fun givenNoTicketIdentity__whenSending__thenReturnSilently() {
        dispatch(VALID_FRAME, null)

        verify(chat, never()).send(anyLong(), anyNonNull())
        assertThat(outbound).isEmpty()
    }

    private fun dispatch(frame: String, user: MemberChatTickets.Identity?) {
        val headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE)
        headers.destination = "/app/chat/send"
        headers.sessionId = "s1"
        headers.sessionAttributes = HashMap()
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON)
        if (user != null) {
            headers.setUser(user)
        }
        handler.handleMessage(
            MessageBuilder.createMessage(frame.toByteArray(StandardCharsets.UTF_8), headers.messageHeaders),
        )
    }

    private fun handler(): SimpAnnotationMethodMessageHandler {
        val capture = MessageChannel { message, _ -> outbound.add(message) }
        val template = SimpMessagingTemplate(capture)
        val context = StaticApplicationContext()
        context.beanFactory.registerSingleton(
            "controller",
            MemberChatSocketController(chat, template, Clock.systemUTC()),
        )
        context.refresh()
        val handler = SimpAnnotationMethodMessageHandler(ExecutorSubscribableChannel(), capture, template)
        handler.setDestinationPrefixes(listOf("/app"))
        handler.messageConverter = MappingJackson2MessageConverter()
        handler.setApplicationContext(context)
        handler.afterPropertiesSet()
        return handler
    }

    private companion object {
        val REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val VALID_FRAME = "{\"recipientId\":8,\"requestId\":\"$REQUEST\",\"body\":\"hi\"}"
    }
}
