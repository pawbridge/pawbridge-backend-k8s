package com.pawbridge.backend.community.internal.chat.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import com.pawbridge.backend.community.support.TestFaker
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpSession
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import java.util.LinkedHashSet

class MemberChatSignalsTest {
    private val messaging = mockk<SimpMessagingTemplate>()
    private val registry = mockk<SimpUserRegistry>()
    private val mapper = ObjectMapper()
    private val signals = MemberChatSignals(
        mockk<StringRedisTemplate>(),
        messaging,
        mockk<MemberChatSessions>(),
        mapper,
        registry,
        "synthetic-test",
    )

    @Test
    fun `내 연결이 둘이고 첫 전송이 실패할 때__메시지 신호가 도착하면__다른 연결로 전달한다`() {
        // given
        val first = mockk<SimpSession>()
        val second = mockk<SimpSession>()
        val member = mockk<SimpUser>()
        val signal = MemberChatModels.Signal(7, "MESSAGE", TestFaker.uuid(), 3)

        every { first.id } returns "first-session"
        every { second.id } returns "second-session"
        every { member.sessions } returns LinkedHashSet(listOf(first, second))
        every { registry.getUser(any<String>()) } returns member
        every {
            messaging.convertAndSendToUser("first-session", "/queue/chat", signal, any<Map<String, Any>>())
        } throws IllegalStateException("synthetic send failure")
        justRun {
            messaging.convertAndSendToUser("second-session", "/queue/chat", signal, any<Map<String, Any>>())
        }

        // when
        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        // then
        verify(exactly = 1) {
            messaging.convertAndSendToUser("first-session", "/queue/chat", signal, any<Map<String, Any>>())
        }
        verify(exactly = 1) {
            messaging.convertAndSendToUser(
                "second-session",
                "/queue/chat",
                signal,
                match<Map<String, Any>> { headers -> "second-session" == SimpMessageHeaderAccessor.getSessionId(headers) },
            )
        }
    }

    @Test
    fun `회원에게 로컬 연결이 없을 때__메시지 신호가 도착하면__다른 회원에게 전달하지 않는다`() {
        // given
        val signal = MemberChatModels.Signal(7, "MESSAGE", TestFaker.uuid(), 3)

        every { registry.getUser(any<String>()) } returns null

        // when
        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        // then
        verify(exactly = 1) { registry.getUser("7") }
        confirmVerified(registry, messaging)
    }
}
