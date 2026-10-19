package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface NoteSendStore {
    Receipt inTransaction(Supplier<Receipt> action);
    void lockMembers(long firstMemberId, long secondMemberId);
    Optional<PreviousRequest> request(long senderId, UUID requestId);
    boolean blocked(long firstMemberId, long secondMemberId);
    long rate(long senderId, Instant since);
    Optional<Reply> reply(long ownerId, UUID noteId, Instant now);
    boolean contextAuthor(String contextType, long contextId, long recipientId);
    void insert(NewNote note, Notification notification);

    record PreviousRequest(UUID noteId, String hash) { }
    record Reply(String direction, Long senderId) { }
    record NewNote(UUID noteId, long senderId, long recipientId, String body, UUID replyTo,
                   String contextType, Long contextId, UUID requestId, String requestHash,
                   Instant createdAt, Instant expiresAt) { }
}
