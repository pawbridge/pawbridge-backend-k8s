package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

fun interface NoteDeletion {
    /** Runs the active guard after the member lock and samples each of the two ownership checks separately. */
    fun delete(
        currentUserId: Long,
        noteId: UUID?,
        requireActive: Runnable,
        now: Supplier<Instant>,
    )

    class MissingOwnedNote : RuntimeException("No currently owned, unexpired private note exists.")
}
