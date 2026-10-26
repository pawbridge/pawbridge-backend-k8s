package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

interface NoteSendStore {
    fun inTransaction(action: Supplier<Receipt>): Receipt

    fun lockMembers(
        firstMemberId: Long,
        secondMemberId: Long,
    )

    fun request(
        senderId: Long,
        requestId: UUID,
    ): Optional<PreviousRequest>

    fun blocked(
        firstMemberId: Long,
        secondMemberId: Long,
    ): Boolean

    fun rate(
        senderId: Long,
        since: Instant,
    ): Long

    fun reply(
        ownerId: Long,
        noteId: UUID,
        now: Instant,
    ): Optional<Reply>

    fun contextAuthor(
        contextType: String,
        contextId: Long,
        recipientId: Long,
    ): Boolean

    fun insert(
        note: NewNote,
        notification: Notification,
    )

    @JvmRecord
    data class PreviousRequest(
        val noteId: UUID?,
        val hash: String?,
    )

    @JvmRecord
    data class Reply(
        val direction: String?,
        val senderId: Long?,
    )

    @JvmRecord
    data class NewNote(
        val noteId: UUID?,
        val senderId: Long,
        val recipientId: Long,
        val body: String,
        val replyTo: UUID?,
        val contextType: String?,
        val contextId: Long?,
        val requestId: UUID,
        val requestHash: String,
        val createdAt: Instant,
        val expiresAt: Instant,
    )
}
