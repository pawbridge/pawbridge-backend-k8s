package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@FunctionalInterface
public interface NoteReadMutation {
    /** Runs the active guard after the member lock and samples each ownership check and update separately. */
    void read(long currentUserId, UUID noteId, Runnable requireActive, Supplier<Instant> now);

    final class MissingOwnedNote extends RuntimeException {
        public MissingOwnedNote() {
            super("No currently owned, unexpired private note exists.");
        }
    }
}
