package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;

public final class RemoveNoteBlock {
    private final ContactMemberQuery members;
    private final NoteBlockRemoval removal;

    public RemoveNoteBlock(ContactMemberQuery members, NoteBlockRemoval removal) {
        this.members = members;
        this.removal = removal;
    }

    public void remove(long currentUserId, long blockedUserId) {
        if (currentUserId <= 0 || blockedUserId <= 0) {
            throw new NoteBlockRemovalRejectedException(NoteBlockRemovalRejectedException.Reason.LOGIN);
        }
        removal.remove(currentUserId, blockedUserId, () -> {
            if (!members.get(currentUserId).active()) {
                throw new NoteBlockRemovalRejectedException(NoteBlockRemovalRejectedException.Reason.INACTIVE);
            }
        });
    }
}
