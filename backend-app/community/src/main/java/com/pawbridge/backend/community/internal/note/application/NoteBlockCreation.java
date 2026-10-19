package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.function.Supplier;

@FunctionalInterface
public interface NoteBlockCreation {
    /** Calls the supplier once after member locks and before the outgoing insert, in one writer transaction. */
    void create(long currentUserId, long blockedUserId, Supplier<Instant> createdAtAfterActiveMembers);
}
