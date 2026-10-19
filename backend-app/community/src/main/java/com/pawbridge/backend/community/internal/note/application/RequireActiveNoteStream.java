package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;

public final class RequireActiveNoteStream {
    private final ContactMemberQuery members;

    public RequireActiveNoteStream(ContactMemberQuery members) {
        this.members = members;
    }

    public void requireActive(long currentUserId) {
        if (currentUserId <= 0) {
            throw new NoteStreamRejectedException(NoteStreamRejectedException.Reason.LOGIN);
        }
        if (!members.get(currentUserId).active()) {
            throw new NoteStreamRejectedException(NoteStreamRejectedException.Reason.INACTIVE);
        }
    }
}
