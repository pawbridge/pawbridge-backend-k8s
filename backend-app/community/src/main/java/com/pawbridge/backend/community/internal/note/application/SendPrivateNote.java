package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason.*;

public final class SendPrivateNote {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ContactMemberQuery members;
    private final NoteSendStore store;
    private final Clock clock;
    private final Supplier<UUID> noteIds;

    public SendPrivateNote(ContactMemberQuery members, NoteSendStore store, Clock clock, Supplier<UUID> noteIds) {
        this.members = members;
        this.store = store;
        this.clock = clock;
        this.noteIds = noteIds;
    }

    public Receipt send(long currentUserId, SendNote request) {
        requireId(currentUserId);
        String body = validate(request, currentUserId);
        String requestHash = hash(request, body);
        return store.inTransaction(() -> {
            store.lockMembers(currentUserId, request.recipientId());
            ContactMember sender = active(currentUserId);
            active(request.recipientId());
            var previousRequest = store.request(currentUserId, request.requestId());
            if (previousRequest.isPresent()) {
                var previous = previousRequest.get();
                if (previous.noteId() == null || previous.hash() == null) throw rejected(GONE);
                if (!requestHash.equals(previous.hash())) throw rejected(CONFLICT);
                return new Receipt(previous.noteId());
            }
            if (store.blocked(currentUserId, request.recipientId())) throw rejected(BLOCKED);
            Instant now = clock.instant();
            Instant startOfKoreanDay = now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant();
            if (store.rate(currentUserId, now.minusSeconds(60)) >= 10
                    || store.rate(currentUserId, startOfKoreanDay) >= 100) throw rejected(RATE_LIMIT);
            if (request.replyTo() != null) {
                var parent = store.reply(currentUserId, request.replyTo(), clock.instant())
                        .orElseThrow(() -> rejected(NOT_FOUND));
                if (!"INBOX".equals(parent.direction()) || !Objects.equals(parent.senderId(), request.recipientId())) {
                    throw rejected(REPLY_MISMATCH);
                }
            }
            if (request.contextType() != null
                    && !store.contextAuthor(request.contextType(), request.contextId(), request.recipientId())) {
                throw rejected(CONTEXT_AUTHOR);
            }
            UUID noteId = noteIds.get();
            Instant expiresAt = now.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
            var note = new NoteSendStore.NewNote(noteId, currentUserId, request.recipientId(), body,
                    request.replyTo(), request.contextType(), request.contextId(), request.requestId(),
                    requestHash, now, expiresAt);
            var notification = new Notification(noteId, "PRIVATE_NOTE", sender.userId(), sender.nickname(),
                    now, false, "/notes/" + noteId);
            store.insert(note, notification);
            return new Receipt(noteId);
        });
    }

    private ContactMember active(long userId) {
        requireId(userId);
        ContactMember member = members.get(userId);
        if (!member.active()) throw rejected(INACTIVE);
        return member;
    }

    private static String validate(SendNote request, long currentUserId) {
        if (request == null || request.recipientId() == null || request.recipientId() <= 0
                || request.recipientId() == currentUserId || request.requestId() == null || request.body() == null) {
            throw rejected(INVALID_REQUEST);
        }
        String body = request.body().strip();
        if (body.isBlank() || body.codePointCount(0, body.length()) > 5000 || body.indexOf('\0') >= 0) {
            throw rejected(INVALID_BODY);
        }
        boolean incompleteContext = (request.contextType() == null) != (request.contextId() == null);
        boolean invalidContext = request.contextType() != null && request.contextId() != null
                && (!List.of("POST", "REPORT").contains(request.contextType()) || request.contextId() <= 0);
        if (incompleteContext || invalidContext) throw rejected(INVALID_CONTEXT);
        return body;
    }

    private static String hash(SendNote request, String body) {
        String payload = request.recipientId() + "\n" + request.replyTo() + "\n"
                + request.contextType() + "\n" + request.contextId() + "\n" + body;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireId(long userId) {
        if (userId <= 0) throw rejected(LOGIN);
    }

    private static NoteSendRejectedException rejected(NoteSendRejectedException.Reason reason) {
        return new NoteSendRejectedException(reason);
    }

    public record SendNote(Long recipientId, String body, UUID requestId, UUID replyTo,
                           String contextType, Long contextId) { }
    public record Receipt(UUID noteId) { }
}
