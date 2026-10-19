package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@FunctionalInterface
public interface NoteFavoriteMutation {
    /** Runs the active guard after the member lock and samples each of the two ownership checks separately. */
    void set(long currentUserId, UUID noteId, boolean favorite, Runnable requireActive, Supplier<Instant> now);

    final class MissingOwnedNote extends RuntimeException {
        public MissingOwnedNote() {
            super("No currently owned, unexpired private note exists.");
        }
    }
}
