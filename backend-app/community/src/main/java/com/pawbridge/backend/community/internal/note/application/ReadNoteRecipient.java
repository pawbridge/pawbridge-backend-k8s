package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;

public final class ReadNoteRecipient {
    private final ContactMemberQuery members;
    private final NoteBlockReader blocks;

    public ReadNoteRecipient(ContactMemberQuery members, NoteBlockReader blocks) {
        this.members = members;
        this.blocks = blocks;
    }

    public ContactMember read(long currentUserId, long recipientId) {
        active(currentUserId);
        if (currentUserId == recipientId || blocks.blocked(currentUserId, recipientId)) {
            throw new RecipientRejectedException(RecipientRejectedException.Reason.BLOCKED);
        }
        return active(recipientId);
    }

    private ContactMember active(long userId) {
        if (userId <= 0) {
            throw new RecipientRejectedException(RecipientRejectedException.Reason.LOGIN);
        }
        ContactMember member = members.get(userId);
        if (!member.active()) {
            throw new RecipientRejectedException(RecipientRejectedException.Reason.INACTIVE);
        }
        return member;
    }
}
