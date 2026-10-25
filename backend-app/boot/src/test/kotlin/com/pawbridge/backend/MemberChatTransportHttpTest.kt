package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatController
import com.pawbridge.backend.community.internal.chat.web.MemberChatExceptionHandler
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.user.api.ContactMember
import io.jsonwebtoken.Jwts
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Date
import java.util.UUID

@WebMvcTest(
    controllers = [MemberChatController::class],
    properties = ["pawbridge.chat.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        MemberChatController::class,
        MemberChatExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class MemberChatTransportHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var chat: MemberChat

    @MockkBean
    lateinit var tickets: MemberChatTickets

    @Test
    fun `검증된 신원과 위조 헤더일 때__방 목록을 조회하면__검증된 소유자만 넘긴다`() {
        // given
        val room = UUID.fromString("00000000-0000-0000-0000-000000000001")

        every { chat.rooms(any(), any()) } returns MemberChatModels.RoomPage(listOf(), null)
        every { chat.messages(any(), any(), any(), any()) } returns MemberChatModels.MessagePage(listOf(), null, 0)

        // when
        val rooms = mvc.perform(
            get("/api/v1/chats")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "99")
                .header("X-Auth-Expires-At", "1"),
        )
        val messages = mvc.perform(
            get("/api/v1/chats/" + room + "/messages")
                .queryParam("before", "1")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        rooms
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(header().doesNotExist("Set-Cookie"))
        messages
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
        verify(exactly = 1) { chat.rooms(7, null) }
        verify(exactly = 1) { chat.messages(7, room, 1L, null) }
        confirmVerified(tickets)
    }

    @Test
    fun `서명된 만료와 위조 만료 헤더일 때__티켓을 발급하면__검증된 만료에만 티켓을 묶는다`() {
        // given
        val expires = (System.currentTimeMillis() / 1000 + 600) * 1000
        val token = Jwts.builder()
            .claim("userId", 7L)
            .claim("role", "ROLE_USER")
            .expiration(Date(expires))
            .signWith(F3TestInputs.KEY)
            .compact()

        every { chat.active(any()) } returns ContactMember(7, "synthetic", true, false)
        every { tickets.issue(any(), any()) } returns MemberChatTickets.Ticket("a".repeat(64), expires)

        // when
        val result = mvc.perform(
            post("/api/v1/chats/connection-ticket")
                .header("Authorization", "Bearer $token")
                .header("X-User-Id", "99")
                .header("X-Auth-Expires-At", Long.MAX_VALUE),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expiresAt").value(expires))
        verifyOrder {
            chat.active(7)
            tickets.issue(7, expires)
        }
        confirmVerified(chat, tickets)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "no-expiry"])
    fun `신뢰할 신원이나 만료가 없을 때__위조 헤더로 티켓을 요청하면__업무 호출 전에 거절한다`(
        mode: String,
    ) {
        // given
        val request = post("/api/v1/chats/connection-ticket")
            .header("X-User-Id", "7")
            .header("X-Auth-Expires-At", Long.MAX_VALUE)
        if (mode == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        if (mode == "no-expiry") {
            request.header(
                "Authorization",
                "Bearer " + Jwts.builder().claim("userId", 7L).claim("role", "ROLE_USER").signWith(F3TestInputs.KEY).compact(),
            )
        }

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isUnauthorized())
        confirmVerified(chat, tickets)
    }

    @Test
    fun `익명 업그레이드 경로와 제공하지 않는 이웃 경로일 때__보안이 매칭하면__원래 소켓 핸드셰이크만 허용한다`() {
        // given
        // This slice has no socket transport: 404 proves security lets the handshake reach routing, not a live connection.

        // when
        val handshake = mvc.perform(get("/api/v1/chats/socket"))
        val neighbor = mvc.perform(get("/api/v1/chats/socket/extra"))

        // then
        handshake.andExpect(status().isNotFound())
        neighbor.andExpect(status().isForbidden())
        confirmVerified(chat, tickets)
    }
}
