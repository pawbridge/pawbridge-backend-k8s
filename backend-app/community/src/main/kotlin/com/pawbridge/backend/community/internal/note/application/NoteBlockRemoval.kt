package com.pawbridge.backend.community.internal.note.application

fun interface NoteBlockRemoval {
    /** Runs the current-member guard once after member locks and before the outgoing delete, in one writer transaction. */
    fun remove(
        currentUserId: Long,
        blockedUserId: Long,
        requireActiveCurrent: Runnable,
    )
}
