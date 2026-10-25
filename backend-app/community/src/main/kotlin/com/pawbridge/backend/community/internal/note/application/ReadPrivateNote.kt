package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.util.UUID

class ReadPrivateNote(
    private val members: ContactMemberQuery,
    private val notes: PrivateNoteReader,
    private val blocks: NoteBlockReader,
    private val clock: Clock,
) {

    fun read(
        currentUserId: Long,
        noteId: UUID?,
    ): PrivateNoteView {
        if (currentUserId <= 0) {
            throw PrivateNoteRejectedException(PrivateNoteRejectedException.Reason.LOGIN)
        }
        if (!members.get(currentUserId).active) {
            throw PrivateNoteRejectedException(PrivateNoteRejectedException.Reason.INACTIVE)
        }
        val note: StoredNote = notes.find(currentUserId, noteId, clock.instant())
            .orElseThrow { PrivateNoteRejectedException(PrivateNoteRejectedException.Reason.NOT_FOUND) }
        val counterpartId = if ("INBOX" == note.direction) note.senderId else note.recipientId
        val counterpart = if (counterpartId == null) {
            ContactMember(null, WITHDRAWN_MEMBER, false, false)
        } else {
            members.get(counterpartId)
        }
        val contextHref = if (note.contextType == null) {
            null
        } else {
            (if ("POST" == note.contextType) "/community/" else "/reports/") + note.contextId
        }
        val canReply = "INBOX" == note.direction &&
            counterpart.active &&
            !blocks.blocked(note.recipientId!!, counterpart.userId!!)
        return PrivateNoteView(
            note.noteId,
            note.body,
            note.direction,
            note.createdAt,
            note.readAt,
            note.favorite,
            if (counterpart.active) counterpart.userId else null,
            if (counterpart.active) counterpart.nickname else WITHDRAWN_MEMBER,
            canReply,
            contextHref,
        )
    }

    private companion object {
        private const val WITHDRAWN_MEMBER = "탈퇴한 회원"
    }
}
