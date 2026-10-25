package com.pawbridge.backend

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import com.pawbridge.backend.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.time.Clock

class MemberChatSessionLifecycleTest {
    @Test
    fun given__authenticated_and_pending_sockets__when__destroying_twice__then__close_each_once_without_member_or_redis_io() {
        val redis = mock(StringRedisTemplate::class.java)

        @Suppress("UNCHECKED_CAST")
        val chat = mock(ObjectProvider::class.java) as ObjectProvider<MemberChat>
        val sessions = MemberChatSessions(redis, chat, Clock.systemUTC(), "synthetic-test")
        val first = mock(WebSocketSession::class.java)
        whenever(first.id).thenReturn("first")
        whenever(first.isOpen).thenReturn(true)
        val second = mock(WebSocketSession::class.java)
        whenever(second.id).thenReturn("second")
        assertThat(sessions.open(first)).isTrue()
        assertThat(sessions.open(second)).isTrue()
        whenever(
            redis.execute(
                anyNonNull<RedisScript<Any>>(),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
            ),
        ).thenReturn(1L)
        sessions.authenticate("first", MemberChatTickets.Identity(7, Long.MAX_VALUE))
        clearInvocations(redis)
        doAnswer(
            Answer<Any?> {
                sessions.removed("first")
                null
            },
        ).`when`(first).close(CloseStatus.POLICY_VIOLATION)
        sessions.destroy()
        sessions.destroy()
        // A delayed transport-disconnect callback must also see the already detached connection.
        sessions.removed("first")
        sessions.removed("second")
        verify(first).close(CloseStatus.POLICY_VIOLATION)
        verify(second).close(CloseStatus.POLICY_VIOLATION)
        verifyNoInteractions(redis, chat)
    }
}
