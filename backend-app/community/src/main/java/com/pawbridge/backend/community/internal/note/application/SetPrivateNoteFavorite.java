package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import java.util.UUID;

public final class SetPrivateNoteFavorite {
    private final ContactMemberQuery members;
    private final NoteFavoriteMutation mutation;
    private final Clock clock;

    public SetPrivateNoteFavorite(ContactMemberQuery members, NoteFavoriteMutation mutation, Clock clock) {
        this.members = members;
        this.mutation = mutation;
        this.clock = clock;
    }

    public void set(long currentUserId, UUID noteId, boolean favorite) {
        try {
            mutation.set(currentUserId, noteId, favorite, () -> {
                if (currentUserId <= 0) {
                    throw new NoteFavoriteRejectedException(NoteFavoriteRejectedException.Reason.LOGIN);
                }
                if (!members.get(currentUserId).active()) {
                    throw new NoteFavoriteRejectedException(NoteFavoriteRejectedException.Reason.INACTIVE);
                }
            }, clock::instant);
        } catch (NoteFavoriteMutation.MissingOwnedNote missing) {
            throw new NoteFavoriteRejectedException(NoteFavoriteRejectedException.Reason.NOT_FOUND);
        }
    }
}
