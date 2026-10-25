package com.pawbridge.backend.community.internal.chat.application

import java.time.Instant
import java.util.UUID

class MemberChatModels private constructor() {

    @JvmRecord
    data class Send(
        val recipientId: Long?,
        val requestId: UUID?,
        val body: String?,
        val contextType: String?,
        val contextId: Long?,
    )

    @JvmRecord
    data class Receipt(
        val requestId: UUID?,
        val roomId: UUID?,
        val sequence: Long,
    )

    @JvmRecord
    data class ExistingRoom(
        val roomId: UUID?,
    )

    @JvmRecord
    data class Message(
        val roomId: UUID?,
        val sequence: Long,
        val senderId: Long?,
        val body: String?,
        val contextHref: String?,
        val createdAt: Instant?,
    )

    @JvmRecord
    data class Room(
        val roomId: UUID?,
        val counterpartId: Long?,
        val counterpartNickname: String?,
        val latestSequence: Long,
        val readThrough: Long,
        val counterpartReadThrough: Long,
        val unread: Boolean,
        val canSend: Boolean,
        val updatedAt: Instant?,
        val preview: String?,
    )

    @JvmRecord
    data class RoomPage(
        val content: List<Room>,
        val nextCursor: UUID?,
    )

    @JvmRecord
    data class MessagePage(
        val content: List<Message>,
        val nextCursor: Long?,
        val counterpartReadThrough: Long,
    )

    @JvmRecord
    data class Notifications(
        val content: List<Room>,
        val nextCursor: UUID?,
        val unreadCount: Long,
    )

    @JvmRecord
    data class Read(
        val sequence: Long,
    )

    /** No private body is carried through Redis or notifications. */
    @JvmRecord
    data class Signal(
        val memberId: Long,
        val kind: String?,
        val roomId: UUID?,
        val sequence: Long,
    )

    @JvmRecord
    data class Failure(
        val requestId: UUID?,
        val status: Int,
        val message: String?,
    )
}
