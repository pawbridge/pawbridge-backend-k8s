package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

class DeletePrivateNote(
    private val members: ContactMemberQuery,
    private val mutation: NoteDeletion,
    private val clock: Clock,
) {

    fun delete(
        currentUserId: Long,
        noteId: UUID?,
    ) {
        try {
            mutation.delete(
                currentUserId,
                noteId,
                Runnable {
                    if (currentUserId <= 0) {
                        throw NoteDeletionRejectedException(NoteDeletionRejectedException.Reason.LOGIN)
                    }
                    if (!members.get(currentUserId).active) {
                        throw NoteDeletionRejectedException(NoteDeletionRejectedException.Reason.INACTIVE)
                    }
                },
                Supplier<Instant> { clock.instant() },
            )
        } catch (missing: NoteDeletion.MissingOwnedNote) {
            throw NoteDeletionRejectedException(NoteDeletionRejectedException.Reason.NOT_FOUND)
        }
    }
}
