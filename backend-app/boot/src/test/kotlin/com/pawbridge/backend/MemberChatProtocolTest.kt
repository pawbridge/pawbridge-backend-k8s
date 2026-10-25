package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.MemberChatSocketConfiguration
import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatProtocolErrors
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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

    private val tickets = mock(MemberChatTickets::class.java)
    private val sessions = mock(MemberChatSessions::class.java)
    private val chat = mock(MemberChat::class.java)

    @Suppress("UNCHECKED_CAST")
    private val sessionProvider = mock(ObjectProvider::class.java) as ObjectProvider<MemberChatSessions>

    @Suppress("UNCHECKED_CAST")
    private val chatProvider = mock(ObjectProvider::class.java) as ObjectProvider<MemberChat>

    private fun inbound(): ChannelInterceptor {
        whenever(sessionProvider.getObject()).thenReturn(sessions)
        whenever(chatProvider.getObject()).thenReturn(chat)
        val config = MemberChatSocketConfiguration(tickets, sessionProvider, chatProvider, "https://fixture.invalid")
        val channel = Channel()
        config.configureClientInboundChannel(channel)
        return channel.values().first()
    }

    @ParameterizedTest
    @ValueSource(strings = ["SEND", "SUBSCRIBE"])
    fun given__anonymous_pending_socket__when__sending_or_subscribing_before_ticket_authentication__then__drop_and_close_without_business(
        command: String,
    ) {
        doThrow(MemberChatRejectedException(401, "expired")).`when`(sessions).requireCurrent("owned")
        assertThat(
            inbound().preSend(
                frame(StompCommand.valueOf(command), if (command == "SEND") "/app/chat/send" else "/user/queue/chat"),
                mock(MessageChannel::class.java),
            ),
        ).isNull()
        verify(sessions).close("owned")
        verifyNoInteractions(tickets, chat)
    }

    @Test
    fun given__atomic_ticket_identity__when__connecting__then__check_active_and_bind_that_identity_once() {
        val identity = MemberChatTickets.Identity(7, Long.MAX_VALUE)
        whenever(tickets.consume("synthetic")).thenReturn(identity)
        val headers = StompHeaderAccessor.create(StompCommand.CONNECT)
        headers.sessionId = "owned"
        headers.addNativeHeader("ticket", "synthetic")
        headers.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(ByteArray(0), headers.messageHeaders)
        assertThat(inbound().preSend(message, mock(MessageChannel::class.java))).isSameAs(message)
        val order = inOrder(tickets, chat, sessions)
        order.verify(tickets).consume("synthetic")
        order.verify(chat).active(7)
        order.verify(sessions).authenticate("owned", identity)
        assertThat(headers.user).isSameAs(identity)
    }

    @Test
    fun given__private_parser_failure__when__producing_protocol_error__then__keep_only_fixed_safe_text() {
        val protocol = MemberChatProtocolErrors()
        val error = protocol.handleClientMessageProcessingError(
            MessageBuilder.withPayload("private-body-token".toByteArray(Charsets.UTF_8)).build(),
            IllegalArgumentException("private-body-token"),
        )
        assertThat(String(error.payload, Charsets.UTF_8)).doesNotContain("private-body-token")
        assertThat(error.headers.toString()).doesNotContain("private-body-token")
    }

    @Test
    fun given__authenticated_socket__when__subscribing_to_another_destination__then__drop_and_close() {
        assertThat(
            inbound().preSend(frame(StompCommand.SUBSCRIBE, "/topic/other"), mock(MessageChannel::class.java)),
        ).isNull()
        verify(sessions).requireCurrent("owned")
        verify(sessions).close("owned")
        verifyNoInteractions(chat, tickets)
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
