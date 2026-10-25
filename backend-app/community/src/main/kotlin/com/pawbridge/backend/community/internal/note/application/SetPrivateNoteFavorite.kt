package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

class SetPrivateNoteFavorite(
    private val members: ContactMemberQuery,
    private val mutation: NoteFavoriteMutation,
    private val clock: Clock,
) {

    fun set(
        currentUserId: Long,
        noteId: UUID?,
        favorite: Boolean,
    ) {
        try {
            mutation.set(
                currentUserId,
                noteId,
                favorite,
                Runnable {
                    if (currentUserId <= 0) {
                        throw NoteFavoriteRejectedException(NoteFavoriteRejectedException.Reason.LOGIN)
                    }
                    if (!members.get(currentUserId).active) {
                        throw NoteFavoriteRejectedException(NoteFavoriteRejectedException.Reason.INACTIVE)
                    }
                },
                Supplier<Instant> { clock.instant() },
            )
        } catch (missing: NoteFavoriteMutation.MissingOwnedNote) {
            throw NoteFavoriteRejectedException(NoteFavoriteRejectedException.Reason.NOT_FOUND)
        }
    }
}
