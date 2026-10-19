package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ReadNoteNotifications {
    private static final int PAGE_SIZE = 20;
    private final ContactMemberQuery members;
    private final NoteNotificationReader notes;
    private final ContactMembersQuery contactMembers;
    private final Clock clock;

    public ReadNoteNotifications(ContactMemberQuery members, NoteNotificationReader notes,
                                 ContactMembersQuery contactMembers, Clock clock) {
        this.members = members;
        this.notes = notes;
        this.contactMembers = contactMembers;
        this.clock = clock;
    }

    public NoteNotifications read(long currentUserId, UUID cursor) {
        if (currentUserId <= 0) {
            throw new NoteNotificationsRejectedException(NoteNotificationsRejectedException.Reason.LOGIN);
        }
        if (!members.get(currentUserId).active()) {
            throw new NoteNotificationsRejectedException(NoteNotificationsRejectedException.Reason.INACTIVE);
        }
        Instant now = clock.instant();
        List<NoteNotificationReader.Row> found = notes.read(currentUserId, cursor, now);
        List<NoteNotificationReader.Row> page = found.stream().limit(PAGE_SIZE).toList();
        List<Long> senderIds = page.stream().map(NoteNotificationReader.Row::senderId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, ContactMember> senders = new HashMap<>();
        if (!senderIds.isEmpty()) {
            for (ContactMember sender : contactMembers.getAll(senderIds)) {
                senders.put(sender.userId(), sender);
            }
        }
        List<Notification> content = page.stream().map(row -> {
            Long actorId = null;
            String nickname = "탈퇴한 회원";
            if (row.senderId() != null) {
                ContactMember sender = senders.get(row.senderId());
                if (sender.active()) {
                    actorId = sender.userId();
                    nickname = sender.nickname();
                }
            }
            return new Notification(row.noteId(), "PRIVATE_NOTE", actorId, nickname, row.createdAt(),
                    row.readAt() != null, "/notes/" + row.noteId());
        }).toList();
        UUID nextCursor = found.size() > PAGE_SIZE ? content.get(content.size() - 1).noteId() : null;
        return new NoteNotifications(content, nextCursor, notes.unread(currentUserId, now));
    }
}
