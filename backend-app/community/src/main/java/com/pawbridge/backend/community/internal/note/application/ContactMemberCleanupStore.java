package com.pawbridge.backend.community.internal.note.application;

/** Executes the pending-state policy after the member lock, inside the cleanup transaction. */
public interface ContactMemberCleanupStore {
    void remove(long memberId, Runnable requireDeletionPending);
}
