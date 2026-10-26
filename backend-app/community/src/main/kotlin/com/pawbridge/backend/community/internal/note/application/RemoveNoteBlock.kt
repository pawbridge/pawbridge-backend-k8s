package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery

class RemoveNoteBlock(
    private val members: ContactMemberQuery,
    private val removal: NoteBlockRemoval,
) {

    fun remove(
        currentUserId: Long,
        blockedUserId: Long,
    ) {
        if (currentUserId <= 0 || blockedUserId <= 0) {
            throw NoteBlockRemovalRejectedException(NoteBlockRemovalRejectedException.Reason.LOGIN)
        }
        removal.remove(
            currentUserId,
            blockedUserId,
            Runnable {
                if (!members.get(currentUserId).active) {
                    throw NoteBlockRemovalRejectedException(NoteBlockRemovalRejectedException.Reason.INACTIVE)
                }
            },
        )
    }
}
