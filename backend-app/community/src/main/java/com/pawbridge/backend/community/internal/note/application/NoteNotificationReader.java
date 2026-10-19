package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NoteNotificationReader {
    List<Row> read(long currentUserId, UUID cursor, Instant now);

    long unread(long currentUserId, Instant now);

    record Row(UUID noteId, Long senderId, Instant createdAt, Instant readAt) {
    }
}
