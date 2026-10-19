package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import java.util.UUID;

public final class DeletePrivateNote {
    private final ContactMemberQuery members;
    private final NoteDeletion mutation;
    private final Clock clock;

    public DeletePrivateNote(ContactMemberQuery members, NoteDeletion mutation, Clock clock) {
        this.members = members;
        this.mutation = mutation;
        this.clock = clock;
    }

    public void delete(long currentUserId, UUID noteId) {
        try {
            mutation.delete(currentUserId, noteId, () -> {
                if (currentUserId <= 0) {
                    throw new NoteDeletionRejectedException(NoteDeletionRejectedException.Reason.LOGIN);
                }
                if (!members.get(currentUserId).active()) {
                    throw new NoteDeletionRejectedException(NoteDeletionRejectedException.Reason.INACTIVE);
                }
            }, clock::instant);
        } catch (NoteDeletion.MissingOwnedNote missing) {
            throw new NoteDeletionRejectedException(NoteDeletionRejectedException.Reason.NOT_FOUND);
        }
    }
}
