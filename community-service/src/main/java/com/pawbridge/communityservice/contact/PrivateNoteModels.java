package com.pawbridge.communityservice.contact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PrivateNoteModels {
    private PrivateNoteModels() {}

    public record ContactMember(Long userId, String nickname, boolean active, boolean deletionPending) {}

    public record SendNote(
            Long recipientId, String body, UUID requestId, UUID replyTo,
            String contextType, Long contextId) {}

    /** Original and mailbox state loaded by the repository; not an HTTP response. */
    public record Note(
            UUID noteId, Long senderId, Long recipientId, String body, UUID replyTo,
            String contextType, Long contextId, Instant createdAt, Instant expiresAt,
            String direction, Instant readAt, boolean favorite) {}

    public record NoteView(
            UUID noteId, String body, String direction, Instant createdAt,
            Instant readAt, boolean favorite, Long counterpartId, String counterpartNickname,
            boolean canReply, String contextHref) {}

    /** REST and SSE share this payload; it excludes body, email and private profile fields. */
    public record Notification(
            UUID noteId, String kind, Long actorId, String actorNickname,
            Instant createdAt, boolean read, String href) {}

    public record Notifications(List<Notification> content, UUID nextCursor, long unreadCount) {}

    public record NotePage(List<NoteView> content, long totalElements, int totalPages, int number, int size) {}

    public record BlockView(Long memberId, String nickname, Instant createdAt) {}

    public record BlockPage(List<BlockView> content, long totalElements, int totalPages, int number) {}

    public record Favorite(boolean favorite) {}

    public record Receipt(UUID noteId) {}
}
