package com.pawbridge.backend.community.internal.note.application;

@FunctionalInterface
public interface NoteBlockRemoval {
    /** Runs the current-member guard once after member locks and before the outgoing delete, in one writer transaction. */
    void remove(long currentUserId, long blockedUserId, Runnable requireActiveCurrent);
}
