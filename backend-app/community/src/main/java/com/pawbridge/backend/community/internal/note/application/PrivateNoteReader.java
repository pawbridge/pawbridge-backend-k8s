package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PrivateNoteReader {
    Optional<StoredNote> find(long currentUserId, UUID noteId, Instant now);

    record StoredNote(UUID noteId, Long senderId, Long recipientId, String body,
                      String contextType, Long contextId, Instant createdAt, String direction,
                      Instant readAt, boolean favorite) {
    }
}
