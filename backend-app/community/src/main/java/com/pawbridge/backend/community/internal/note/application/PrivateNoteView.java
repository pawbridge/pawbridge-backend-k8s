package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.UUID;

public record PrivateNoteView(UUID noteId, String body, String direction, Instant createdAt,
                              Instant readAt, boolean favorite, Long counterpartId, String counterpartNickname,
                              boolean canReply, String contextHref) {
}
