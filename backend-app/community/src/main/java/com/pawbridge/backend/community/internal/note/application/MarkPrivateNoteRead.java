package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import java.util.UUID;

public final class MarkPrivateNoteRead {
    private final ContactMemberQuery members;
    private final NoteReadMutation mutation;
    private final Clock clock;

    public MarkPrivateNoteRead(ContactMemberQuery members, NoteReadMutation mutation, Clock clock) {
        this.members = members;
        this.mutation = mutation;
        this.clock = clock;
    }

    public void read(long currentUserId, UUID noteId) {
        try {
            mutation.read(currentUserId, noteId, () -> {
                if (currentUserId <= 0) {
                    throw new NoteReadMutationRejectedException(NoteReadMutationRejectedException.Reason.LOGIN);
                }
                if (!members.get(currentUserId).active()) {
                    throw new NoteReadMutationRejectedException(NoteReadMutationRejectedException.Reason.INACTIVE);
                }
            }, clock::instant);
        } catch (NoteReadMutation.MissingOwnedNote missing) {
            throw new NoteReadMutationRejectedException(NoteReadMutationRejectedException.Reason.NOT_FOUND);
        }
    }
}
