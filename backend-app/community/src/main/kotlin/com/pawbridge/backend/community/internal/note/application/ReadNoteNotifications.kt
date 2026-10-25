package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery
import java.time.Clock
import java.util.UUID

class ReadNoteNotifications(
    private val members: ContactMemberQuery,
    private val notes: NoteNotificationReader,
    private val contactMembers: ContactMembersQuery,
    private val clock: Clock,
) {

    fun read(
        currentUserId: Long,
        cursor: UUID?,
    ): NoteNotifications {
        if (currentUserId <= 0) {
            throw NoteNotificationsRejectedException(NoteNotificationsRejectedException.Reason.LOGIN)
        }
        if (!members.get(currentUserId).active) {
            throw NoteNotificationsRejectedException(NoteNotificationsRejectedException.Reason.INACTIVE)
        }
        val now = clock.instant()
        val found = notes.read(currentUserId, cursor, now)
        val page = found.take(PAGE_SIZE)
        val senderIds = page.mapNotNull { row -> row.senderId }.distinct()
        val senders = HashMap<Long?, ContactMember>()
        if (senderIds.isNotEmpty()) {
            for (sender in contactMembers.getAll(senderIds)) {
                senders[sender.userId] = sender
            }
        }
        val content = page.map { row ->
            var actorId: Long? = null
            var nickname: String? = WITHDRAWN_MEMBER
            if (row.senderId != null) {
                // A missing member projection fails here, as the Java original dereferenced null.
                val sender = senders[row.senderId]!!
                if (sender.active) {
                    actorId = sender.userId
                    nickname = sender.nickname
                }
            }
            Notification(
                row.noteId,
                "PRIVATE_NOTE",
                actorId,
                nickname,
                row.createdAt,
                row.readAt != null,
                "/notes/" + row.noteId,
            )
        }
        val nextCursor = if (found.size > PAGE_SIZE) content[content.size - 1].noteId else null
        return NoteNotifications(content, nextCursor, notes.unread(currentUserId, now))
    }

    private companion object {
        private const val PAGE_SIZE = 20
        private const val WITHDRAWN_MEMBER = "탈퇴한 회원"
    }
}
