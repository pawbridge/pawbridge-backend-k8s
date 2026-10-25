package com.pawbridge.backend

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import java.time.Clock

class MemberChatSessionLifecycleTest {
    @Test
    fun `인증된 소켓과 대기 중 소켓이 있을 때__두 번 파기하면__회원이나 Redis 접근 없이 소켓마다 한 번만 닫는다`() {
        // given
        val redis = mockk<StringRedisTemplate>()
        val chat = mockk<ObjectProvider<MemberChat>>()
        val sessions = MemberChatSessions(redis, chat, Clock.systemUTC(), "synthetic-test")
        val first = mockk<WebSocketSession>()
        val second = mockk<WebSocketSession>()

        every { first.id } returns "first"
        every { first.isOpen } returns true
        every { second.id } returns "second"
        every { redis.execute(any<RedisScript<Any>>(), any<List<String>>(), any<String>(), any<String>(), any<String>()) } returns 1L
        every { first.close(CloseStatus.POLICY_VIOLATION) } answers { sessions.removed("first") }
        justRun { second.close(CloseStatus.POLICY_VIOLATION) }

        assertThat(sessions.open(first)).isTrue()
        assertThat(sessions.open(second)).isTrue()
        sessions.authenticate("first", MemberChatTickets.Identity(7, Long.MAX_VALUE))
        clearMocks(redis, answers = false)

        // when
        sessions.destroy()
        sessions.destroy()
        // A delayed transport-disconnect callback must also see the already detached connection.
        sessions.removed("first")
        sessions.removed("second")

        // then
        verify(exactly = 1) { first.close(CloseStatus.POLICY_VIOLATION) }
        verify(exactly = 1) { second.close(CloseStatus.POLICY_VIOLATION) }
        confirmVerified(redis, chat)
    }
}
