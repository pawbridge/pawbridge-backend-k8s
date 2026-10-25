package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery

class RequireActiveNoteStream(
    private val members: ContactMemberQuery,
) {

    fun requireActive(currentUserId: Long) {
        if (currentUserId <= 0) {
            throw NoteStreamRejectedException(NoteStreamRejectedException.Reason.LOGIN)
        }
        if (!members.get(currentUserId).active) {
            throw NoteStreamRejectedException(NoteStreamRejectedException.Reason.INACTIVE)
        }
    }
}
