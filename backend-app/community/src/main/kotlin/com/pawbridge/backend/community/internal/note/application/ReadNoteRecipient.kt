package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery

class ReadNoteRecipient(
    private val members: ContactMemberQuery,
    private val blocks: NoteBlockReader,
) {

    fun read(
        currentUserId: Long,
        recipientId: Long,
    ): ContactMember {
        active(currentUserId)
        if (currentUserId == recipientId || blocks.blocked(currentUserId, recipientId)) {
            throw RecipientRejectedException(RecipientRejectedException.Reason.BLOCKED)
        }
        return active(recipientId)
    }

    private fun active(userId: Long): ContactMember {
        if (userId <= 0) {
            throw RecipientRejectedException(RecipientRejectedException.Reason.LOGIN)
        }
        val member = members.get(userId)
        if (!member.active) {
            throw RecipientRejectedException(RecipientRejectedException.Reason.INACTIVE)
        }
        return member
    }
}
