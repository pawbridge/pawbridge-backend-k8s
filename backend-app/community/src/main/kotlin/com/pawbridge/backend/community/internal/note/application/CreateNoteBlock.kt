package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.util.function.Supplier

class CreateNoteBlock(
    private val members: ContactMemberQuery,
    private val creation: NoteBlockCreation,
    private val clock: Clock,
) {

    fun create(
        currentUserId: Long,
        blockedUserId: Long,
    ) {
        if (currentUserId <= 0 || blockedUserId <= 0) {
            throw NoteBlockCreationRejectedException(NoteBlockCreationRejectedException.Reason.LOGIN)
        }
        if (currentUserId == blockedUserId) {
            throw NoteBlockCreationRejectedException(NoteBlockCreationRejectedException.Reason.SELF)
        }
        creation.create(
            currentUserId,
            blockedUserId,
            Supplier {
                requireActive(currentUserId)
                requireActive(blockedUserId)
                clock.instant()
            },
        )
    }

    private fun requireActive(userId: Long) {
        if (!members.get(userId).active) {
            throw NoteBlockCreationRejectedException(NoteBlockCreationRejectedException.Reason.INACTIVE)
        }
    }
}
