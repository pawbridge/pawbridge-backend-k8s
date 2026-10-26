package com.pawbridge.backend.community.internal.chat.application

import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.ExistingRoom
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.MessagePage
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Notifications
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Room
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.RoomPage
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.user.api.ContactMember
import java.util.UUID

interface MemberChat {
    fun send(sender: Long, input: Send?): Receipt

    fun existingRoom(owner: Long, counterpartId: Long): ExistingRoom

    fun rooms(owner: Long, cursor: UUID?): RoomPage

    fun room(owner: Long, room: UUID?): Room

    fun notifications(owner: Long, cursor: UUID?): Notifications

    fun messages(owner: Long, room: UUID?, before: Long?, after: Long?): MessagePage

    fun read(owner: Long, room: UUID?, through: Long)

    fun hide(owner: Long, room: UUID?)

    fun active(memberId: Long): ContactMember

    fun expire()
}
