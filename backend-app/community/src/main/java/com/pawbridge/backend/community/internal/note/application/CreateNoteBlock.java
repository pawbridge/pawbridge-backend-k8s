package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;

public final class CreateNoteBlock {
    private final ContactMemberQuery members;
    private final NoteBlockCreation creation;
    private final Clock clock;

    public CreateNoteBlock(ContactMemberQuery members, NoteBlockCreation creation, Clock clock) {
        this.members = members;
        this.creation = creation;
        this.clock = clock;
    }

    public void create(long currentUserId, long blockedUserId) {
        if (currentUserId <= 0 || blockedUserId <= 0) {
            throw new NoteBlockCreationRejectedException(NoteBlockCreationRejectedException.Reason.LOGIN);
        }
        if (currentUserId == blockedUserId) {
            throw new NoteBlockCreationRejectedException(NoteBlockCreationRejectedException.Reason.SELF);
        }
        creation.create(currentUserId, blockedUserId, () -> {
            requireActive(currentUserId);
            requireActive(blockedUserId);
            return clock.instant();
        });
    }

    private void requireActive(long userId) {
        if (!members.get(userId).active()) {
            throw new NoteBlockCreationRejectedException(NoteBlockCreationRejectedException.Reason.INACTIVE);
        }
    }
}
