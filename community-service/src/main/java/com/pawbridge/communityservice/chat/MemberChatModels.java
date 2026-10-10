package com.pawbridge.communityservice.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberChatModels {
    private MemberChatModels() {}

    public record Send(Long recipientId, UUID requestId, String body, String contextType, Long contextId) {}
    public record Receipt(UUID requestId, UUID roomId, long sequence) {}
    public record Message(UUID roomId, long sequence, Long senderId, String body,
                          String contextHref, Instant createdAt) {}
    public record Room(UUID roomId, Long counterpartId, String counterpartNickname,
                       long latestSequence, long readThrough, long counterpartReadThrough,
                       boolean unread, boolean canSend, Instant updatedAt, String preview) {}
    public record RoomPage(List<Room> content, UUID nextCursor) {}
    public record MessagePage(List<Message> content, Long nextCursor, long counterpartReadThrough) {}
    public record Notifications(List<Room> content, UUID nextCursor, long unreadCount) {}
    public record Read(long sequence) {}
    /** No private body is carried through Redis or notifications. */
    public record Signal(long memberId, String kind, UUID roomId, long sequence) {}
    public record Failure(UUID requestId, int status, String message) {}
    public record Withdrawn(long memberId) {}
}
