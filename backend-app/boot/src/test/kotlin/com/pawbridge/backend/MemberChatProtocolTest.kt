package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.MemberChatSocketConfiguration
import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatProtocolErrors
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import com.pawbridge.backend.user.api.ContactMember
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder

class MemberChatProtocolTest {
    class Channel : ChannelRegistration() {
        fun values(): List<ChannelInterceptor> = interceptors
    }

    private val tickets = mockk<MemberChatTickets>()
    private val sessions = mockk<MemberChatSessions>()
    private val chat = mockk<MemberChat>()
    private val sessionProvider = mockk<ObjectProvider<MemberChatSessions>>()
    private val chatProvider = mockk<ObjectProvider<MemberChat>>()

    private fun inbound(): ChannelInterceptor {
        val config = MemberChatSocketConfiguration(tickets, sessionProvider, chatProvider, "https://fixture.invalid")
        val channel = Channel()
        config.configureClientInboundChannel(channel)
        return channel.values().first()
    }

    @ParameterizedTest
    @ValueSource(strings = ["SEND", "SUBSCRIBE"])
    fun `인증 전 익명 소켓일 때__티켓 인증 전에 보내기나 구독을 하면__업무 호출 없이 프레임을 버리고 연결을 닫는다`(
        command: String,
    ) {
        // given
        every { sessionProvider.getObject() } returns sessions
        every { chatProvider.getObject() } returns chat
        every { sessions.requireCurrent(any()) } throws MemberChatRejectedException(401, "expired")
        justRun { sessions.close(any()) }

        val destination = if (command == "SEND") "/app/chat/send" else "/user/queue/chat"
        val frame = frame(StompCommand.valueOf(command), destination)

        // when
        val result = inbound().preSend(frame, mockk<MessageChannel>())

        // then
        assertThat(result).isNull()
        verify(exactly = 1) { sessions.close("owned") }
        confirmVerified(tickets, chat)
    }

    @Test
    fun `원자적 티켓 신원일 때__연결하면__활성 회원을 확인하고 그 신원을 한 번만 바인딩한다`() {
        // given
        val identity = MemberChatTickets.Identity(7, Long.MAX_VALUE)
        val headers = StompHeaderAccessor.create(StompCommand.CONNECT)
        headers.sessionId = "owned"
        headers.addNativeHeader("ticket", "synthetic")
        headers.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(ByteArray(0), headers.messageHeaders)

        every { sessionProvider.getObject() } returns sessions
        every { chatProvider.getObject() } returns chat
        every { tickets.consume(any()) } returns identity
        every { chat.active(any()) } returns ContactMember(7, "synthetic", true, false)
        justRun { sessions.authenticate(any(), any()) }

        // when
        val result = inbound().preSend(message, mockk<MessageChannel>())

        // then
        assertThat(result).isSameAs(message)
        verifyOrder {
            tickets.consume("synthetic")
            chat.active(7)
            sessions.authenticate("owned", identity)
        }
        assertThat(headers.user).isSameAs(identity)
    }

    @Test
    fun `내부 파서 실패일 때__프로토콜 오류를 만들면__고정된 안전한 문구만 남긴다`() {
        // given
        val protocol = MemberChatProtocolErrors()
        val message = MessageBuilder.withPayload("private-body-token".toByteArray(Charsets.UTF_8)).build()

        // when
        val error = protocol.handleClientMessageProcessingError(message, IllegalArgumentException("private-body-token"))

        // then
        assertThat(String(error.payload, Charsets.UTF_8)).doesNotContain("private-body-token")
        assertThat(error.headers.toString()).doesNotContain("private-body-token")
    }

    @Test
    fun `인증된 소켓일 때__다른 목적지를 구독하면__프레임을 버리고 연결을 닫는다`() {
        // given
        every { sessionProvider.getObject() } returns sessions
        every { chatProvider.getObject() } returns chat
        justRun { sessions.requireCurrent(any()) }
        justRun { sessions.close(any()) }

        val frame = frame(StompCommand.SUBSCRIBE, "/topic/other")

        // when
        val result = inbound().preSend(frame, mockk<MessageChannel>())

        // then
        assertThat(result).isNull()
        verify(exactly = 1) { sessions.requireCurrent("owned") }
        verify(exactly = 1) { sessions.close("owned") }
        confirmVerified(chat, tickets)
    }

    companion object {
        private fun frame(command: StompCommand, destination: String): Message<ByteArray> {
            val headers = StompHeaderAccessor.create(command)
            headers.sessionId = "owned"
            headers.destination = destination
            headers.setLeaveMutable(true)
            return MessageBuilder.createMessage(ByteArray(0), headers.messageHeaders)
        }
    }
}
