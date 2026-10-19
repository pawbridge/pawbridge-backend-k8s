package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ListPrivateNotes {
    private static final int PAGE_SIZE = 10;
    private final ContactMemberQuery members;
    private final PrivateNoteListReader notes;
    private final ContactMembersQuery contactMembers;
    private final NoteBlockReader blocks;
    private final Clock clock;

    public ListPrivateNotes(ContactMemberQuery members, PrivateNoteListReader notes, ContactMembersQuery contactMembers,
                            NoteBlockReader blocks, Clock clock) {
        this.members = members;
        this.notes = notes;
        this.contactMembers = contactMembers;
        this.blocks = blocks;
        this.clock = clock;
    }

    public PrivateNotePage read(long currentUserId, String box, boolean favorites, int page) {
        if (page < 0 || page > 10000) {
            throw new PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.INVALID_PAGE);
        }
        if (!List.of("INBOX", "SENT").contains(box)) {
            throw new PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.INVALID_BOX);
        }
        if (currentUserId <= 0) {
            throw new PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.LOGIN);
        }
        if (!members.get(currentUserId).active()) {
            throw new PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.INACTIVE);
        }
        Instant now = clock.instant();
        long count = notes.count(currentUserId, box, favorites, now);
        List<StoredNote> found = notes.read(currentUserId, box, favorites, page, now);
        List<Long> counterpartIds = found.stream().map(ListPrivateNotes::counterpartId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, ContactMember> membersById = new HashMap<>();
        if (!counterpartIds.isEmpty()) {
            for (ContactMember member : contactMembers.getAll(counterpartIds)) {
                membersById.put(member.userId(), member);
            }
        }
        List<PrivateNoteView> content = found.stream().map(note -> {
            Long counterpartId = counterpartId(note);
            ContactMember counterpart = counterpartId == null
                    ? new ContactMember(null, "탈퇴한 회원", false, false) : membersById.get(counterpartId);
            String contextHref = note.contextType() == null ? null
                    : ("POST".equals(note.contextType()) ? "/community/" : "/reports/") + note.contextId();
            boolean canReply = "INBOX".equals(note.direction()) && counterpart.active()
                    && !blocks.blocked(note.recipientId(), counterpart.userId());
            return new PrivateNoteView(note.noteId(), note.body(), note.direction(), note.createdAt(), note.readAt(), note.favorite(),
                    counterpart.active() ? counterpart.userId() : null,
                    counterpart.active() ? counterpart.nickname() : "탈퇴한 회원", canReply, contextHref);
        }).toList();
        return new PrivateNotePage(content, count, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE), page, PAGE_SIZE);
    }

    private static Long counterpartId(StoredNote note) {
        return "INBOX".equals(note.direction()) ? note.senderId() : note.recipientId();
    }
}
