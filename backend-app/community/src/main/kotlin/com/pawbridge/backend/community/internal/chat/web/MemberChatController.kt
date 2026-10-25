package com.pawbridge.backend.community.internal.chat.web

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.ExistingRoom
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.MessagePage
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Notifications
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Read
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Room
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.RoomPage
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import java.time.Instant
import java.util.UUID
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/chats")
class MemberChatController(
    private val service: MemberChat,
    private val tickets: MemberChatTickets,
) {

    @GetMapping(ROOM_PATH)
    fun room(
        @AuthenticationPrincipal owner: Long?,
        @PathVariable("room") room: UUID,
    ): Response<Room> = ok(service.room(owner!!, room))

    @GetMapping("/with/{member}")
    fun existingRoom(
        @AuthenticationPrincipal owner: Long?,
        @PathVariable("member") member: Long,
    ): Response<ExistingRoom> = ok(service.existingRoom(owner!!, member))

    @GetMapping
    fun rooms(
        @AuthenticationPrincipal owner: Long?,
        @RequestParam(name = "cursor", required = false) cursor: UUID?,
    ): Response<RoomPage> = ok(service.rooms(owner!!, cursor))

    @GetMapping("/notifications")
    fun notifications(
        @AuthenticationPrincipal owner: Long?,
        @RequestParam(name = "cursor", required = false) cursor: UUID?,
    ): Response<Notifications> = ok(service.notifications(owner!!, cursor))

    @GetMapping(ROOM_PATH + "/messages")
    fun messages(
        @AuthenticationPrincipal owner: Long?,
        @PathVariable("room") room: UUID,
        @RequestParam(name = "before", required = false) before: Long?,
        @RequestParam(name = "after", required = false) after: Long?,
    ): Response<MessagePage> = ok(service.messages(owner!!, room, before, after))

    @PutMapping(ROOM_PATH + "/read")
    fun read(
        @AuthenticationPrincipal owner: Long?,
        @PathVariable("room") room: UUID,
        @RequestBody request: Read,
    ) {
        service.read(owner!!, room, request.sequence)
    }

    @DeleteMapping(ROOM_PATH + "/visibility")
    fun hide(
        @AuthenticationPrincipal owner: Long?,
        @PathVariable("room") room: UUID,
    ) {
        service.hide(owner!!, room)
    }

    @PostMapping("/connection-ticket")
    fun ticket(authentication: Authentication?): Response<MemberChatTickets.Ticket> {
        if (authentication == null) {
            throw MemberChatRejectedException(401, "로그인이 만료되었습니다.")
        }
        val owner = authentication.principal as? Long
            ?: throw MemberChatRejectedException(401, "로그인이 만료되었습니다.")
        val expires = authentication.details as? Instant
            ?: throw MemberChatRejectedException(401, "로그인이 만료되었습니다.")
        service.active(owner)
        return ok(tickets.issue(owner, expires.toEpochMilli()))
    }

    private fun <T> ok(data: T): Response<T> = Response(200, "요청이 성공적으로 처리되었습니다.", data)

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )

    private companion object {
        private const val ROOM_PATH = "/{room:[0-9a-fA-F-]{36}}"
    }
}
