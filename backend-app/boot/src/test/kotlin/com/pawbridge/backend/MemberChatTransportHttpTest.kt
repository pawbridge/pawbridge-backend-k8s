package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import com.pawbridge.backend.community.internal.chat.web.MemberChatController
import com.pawbridge.backend.community.internal.chat.web.MemberChatExceptionHandler
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var chat: MemberChat

    @MockitoBean
    lateinit var tickets: MemberChatTickets

    @Test
    fun given__verified_identity_and_forged_headers__when__listing_rooms__then__pass_only_verified_owner() {
        whenever(chat.rooms(7, null)).thenReturn(MemberChatModels.RoomPage(listOf(), null))
        mvc.perform(
            get("/api/v1/chats")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "99")
                .header("X-Auth-Expires-At", "1"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(header().doesNotExist("Set-Cookie"))
        val room = UUID.fromString("00000000-0000-0000-0000-000000000001")
        whenever(chat.messages(7, room, 1L, null)).thenReturn(MemberChatModels.MessagePage(listOf(), null, 0))
        mvc.perform(
            get("/api/v1/chats/" + room + "/messages")
                .queryParam("before", "1")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
        verify(chat).rooms(7, null)
        verify(chat).messages(7, room, 1L, null)
        verifyNoInteractions(tickets)
    }

    @Test
    fun given__signed_expiry_and_forged_expiry_header__when__issuing_ticket__then__bind_ticket_to_the_verified_expiry_only() {
        val expires = (System.currentTimeMillis() / 1000 + 600) * 1000
        val token = Jwts.builder()
            .claim("userId", 7L)
            .claim("role", "ROLE_USER")
            .expiration(Date(expires))
            .signWith(F3TestInputs.KEY)
            .compact()
        whenever(tickets.issue(7, expires)).thenReturn(MemberChatTickets.Ticket("a".repeat(64), expires))
        mvc.perform(
            post("/api/v1/chats/connection-ticket")
                .header("Authorization", "Bearer $token")
                .header("X-User-Id", "99")
                .header("X-Auth-Expires-At", Long.MAX_VALUE),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expiresAt").value(expires))
        val order = inOrder(chat, tickets)
        order.verify(chat).active(7)
        order.verify(tickets).issue(7, expires)
        order.verifyNoMoreInteractions()
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "no-expiry"])
    fun given__no_trusted_identity_or_expiry__when__requesting_ticket_with_forged_headers__then__reject_before_business(
        mode: String,
    ) {
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
        mvc.perform(request).andExpect(status().isUnauthorized())
        verifyNoInteractions(chat, tickets)
    }

    @Test
    fun given__anonymous_upgrade_path_and_unported_neighbor__when__security_matches__then__permit_only_original_socket_handshake() {
        // This slice has no socket transport: 404 proves security lets the handshake reach routing, not a live connection.
        mvc.perform(get("/api/v1/chats/socket")).andExpect(status().isNotFound())
        mvc.perform(get("/api/v1/chats/socket/extra")).andExpect(status().isForbidden())
        verifyNoInteractions(chat, tickets)
    }
}
