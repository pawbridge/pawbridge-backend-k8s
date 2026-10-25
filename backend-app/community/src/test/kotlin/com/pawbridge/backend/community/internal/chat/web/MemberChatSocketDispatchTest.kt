package com.pawbridge.backend.community.internal.chat.web

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Failure
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.support.TestFaker
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    private val chat = mockk<MemberChat>()
    private val handler = handler()

    @Test
    fun `티켓 신원과 올바른 프레임이 있을 때__전송하면__서비스가 받고 영수증은 세션으로 간다`() {
        // given
        val receipt = Receipt(REQUEST, TestFaker.uuid(), 1)

        every { chat.send(any(), any()) } returns receipt

        // when
        dispatch(VALID_FRAME, MemberChatTickets.Identity(7, Long.MAX_VALUE))

        // then
        verify(exactly = 1) { chat.send(7, Send(8L, REQUEST, "hi", null, null)) }
        confirmVerified(chat)
        assertThat(outbound).singleElement().satisfies({ message ->
            assertThat(SimpMessageHeaderAccessor.getDestination(message.headers)).isEqualTo("/user/s1/queue/chat")
            assertThat(message.payload).isEqualTo(receipt)
        })
    }

    @Test
    fun `빈 페이로드일 때__전송하면__아무것도 보내지 않고 잘못된 프레임으로 응답한다`() {
        // given
        val frame = ""

        // when
        dispatch(frame, MemberChatTickets.Identity(7, Long.MAX_VALUE))

        // then
        verify(exactly = 0) { chat.send(any(), any()) }
        confirmVerified(chat)
        assertThat(outbound).singleElement().satisfies({ message ->
            assertThat(message.payload).isEqualTo(Failure(null, 400, "메시지 형식을 확인해 주세요."))
        })
    }

    @Test
    fun `티켓 신원이 없을 때__전송하면__조용히 반환한다`() {
        // given
        val user: MemberChatTickets.Identity? = null

        // when
        dispatch(VALID_FRAME, user)

        // then
        verify(exactly = 0) { chat.send(any(), any()) }
        confirmVerified(chat)
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
