package com.pawbridge.backend.community.internal.chat.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpSession
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import java.util.LinkedHashSet
import java.util.UUID

class MemberChatSignalsTest {
    private val messaging = mock(SimpMessagingTemplate::class.java)
    private val registry = mock(SimpUserRegistry::class.java)
    private val mapper = ObjectMapper()
    private val signals = MemberChatSignals(
        mock(StringRedisTemplate::class.java),
        messaging,
        mock(MemberChatSessions::class.java),
        mapper,
        registry,
        "synthetic-test",
    )

    @Test
    fun givenTwoOwnConnectionsAndFirstSendFailure_whenMessageSignalArrives_thenDeliverToOtherConnection() {
        val first = mock(SimpSession::class.java)
        whenever(first.id).thenReturn("first-session")
        val second = mock(SimpSession::class.java)
        whenever(second.id).thenReturn("second-session")
        val member = mock(SimpUser::class.java)
        whenever(member.sessions).thenReturn(LinkedHashSet(listOf(first, second)))
        whenever(registry.getUser("7")).thenReturn(member)
        val signal = MemberChatModels.Signal(7, "MESSAGE", UUID.randomUUID(), 3)
        doThrow(IllegalStateException("synthetic send failure")).`when`(messaging)
            .convertAndSendToUser(eq("first-session"), eq("/queue/chat"), eq(signal), anyMap())

        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        verify(messaging).convertAndSendToUser(eq("first-session"), eq("/queue/chat"), eq(signal), anyMap())
        verify(messaging).convertAndSendToUser(
            eq("second-session"),
            eq("/queue/chat"),
            eq(signal),
            argThat<Map<String, Any>> { headers -> "second-session" == SimpMessageHeaderAccessor.getSessionId(headers) },
        )
    }

    @Test
    fun givenMemberWithoutLocalConnections_whenMessageSignalArrives_thenDoNotForwardToOtherMembers() {
        val signal = MemberChatModels.Signal(7, "MESSAGE", UUID.randomUUID(), 3)

        signals.onMessage(DefaultMessage(ByteArray(0), mapper.writeValueAsBytes(signal)), null)

        verify(registry).getUser("7")
        verifyNoMoreInteractions(registry)
        verifyNoInteractions(messaging)
    }
}
