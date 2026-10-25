package com.pawbridge.backend.community.internal.note.application

interface ContactMemberCleanupStore {
    fun remove(
        memberId: Long,
        requireDeletionPending: Runnable,
    )
}
