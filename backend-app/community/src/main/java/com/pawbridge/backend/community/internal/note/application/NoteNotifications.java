package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NoteNotifications(List<Notification> content, UUID nextCursor, long unreadCount) {
    public record Notification(UUID noteId, String kind, Long actorId, String actorNickname,
                               Instant createdAt, boolean read, String href) {
    }
}
