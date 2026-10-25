package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

fun interface NoteReadMutation {
    /** Runs the active guard after the member lock and samples each ownership check and update separately. */
    fun read(
        currentUserId: Long,
        noteId: UUID?,
        requireActive: Runnable,
        now: Supplier<Instant>,
    )

    class MissingOwnedNote : RuntimeException("No currently owned, unexpired private note exists.")
}
