package com.pawbridge.backend.community.internal.note.application

import java.time.Instant
import java.util.function.Supplier

fun interface NoteBlockCreation {
    /** Calls the supplier once after member locks and before the outgoing insert, in one writer transaction. */
    fun create(
        currentUserId: Long,
        blockedUserId: Long,
        createdAtAfterActiveMembers: Supplier<Instant>,
    )
}
