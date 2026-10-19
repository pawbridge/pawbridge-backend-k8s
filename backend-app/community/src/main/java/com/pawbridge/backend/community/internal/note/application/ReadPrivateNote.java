package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import java.util.UUID;

public final class ReadPrivateNote {
    private final ContactMemberQuery members;
    private final PrivateNoteReader notes;
    private final NoteBlockReader blocks;
    private final Clock clock;

    public ReadPrivateNote(ContactMemberQuery members, PrivateNoteReader notes, NoteBlockReader blocks, Clock clock) {
        this.members = members;
        this.notes = notes;
        this.blocks = blocks;
        this.clock = clock;
    }

    public PrivateNoteView read(long currentUserId, UUID noteId) {
        if (currentUserId <= 0) {
            throw new PrivateNoteRejectedException(PrivateNoteRejectedException.Reason.LOGIN);
        }
        if (!members.get(currentUserId).active()) {
            throw new PrivateNoteRejectedException(PrivateNoteRejectedException.Reason.INACTIVE);
        }
        StoredNote note = notes.find(currentUserId, noteId, clock.instant())
                .orElseThrow(() -> new PrivateNoteRejectedException(PrivateNoteRejectedException.Reason.NOT_FOUND));
        Long counterpartId = "INBOX".equals(note.direction()) ? note.senderId() : note.recipientId();
        ContactMember counterpart = counterpartId == null
                ? new ContactMember(null, "탈퇴한 회원", false, false) : members.get(counterpartId);
        String contextHref = note.contextType() == null ? null
                : ("POST".equals(note.contextType()) ? "/community/" : "/reports/") + note.contextId();
        boolean canReply = "INBOX".equals(note.direction()) && counterpart.active()
                && !blocks.blocked(note.recipientId(), counterpart.userId());
        return new PrivateNoteView(note.noteId(), note.body(), note.direction(), note.createdAt(), note.readAt(), note.favorite(),
                counterpart.active() ? counterpart.userId() : null,
                counterpart.active() ? counterpart.nickname() : "탈퇴한 회원", canReply, contextHref);
    }
}
