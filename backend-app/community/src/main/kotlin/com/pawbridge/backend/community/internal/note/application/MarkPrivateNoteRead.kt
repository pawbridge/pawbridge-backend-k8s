package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

class MarkPrivateNoteRead(
    private val members: ContactMemberQuery,
    private val mutation: NoteReadMutation,
    private val clock: Clock,
) {

    fun read(
        currentUserId: Long,
        noteId: UUID?,
    ) {
        try {
            mutation.read(
                currentUserId,
                noteId,
                Runnable {
                    if (currentUserId <= 0) {
                        throw NoteReadMutationRejectedException(NoteReadMutationRejectedException.Reason.LOGIN)
                    }
                    if (!members.get(currentUserId).active) {
                        throw NoteReadMutationRejectedException(NoteReadMutationRejectedException.Reason.INACTIVE)
                    }
                },
                Supplier<Instant> { clock.instant() },
            )
        } catch (missing: NoteReadMutation.MissingOwnedNote) {
            throw NoteReadMutationRejectedException(NoteReadMutationRejectedException.Reason.NOT_FOUND)
        }
    }
}
