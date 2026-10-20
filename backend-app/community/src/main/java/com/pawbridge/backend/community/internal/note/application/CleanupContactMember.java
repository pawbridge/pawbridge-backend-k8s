package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.api.ContactMemberCleanup;
import com.pawbridge.backend.user.api.ContactMemberQuery;

public final class CleanupContactMember implements ContactMemberCleanup {
    private final ContactMemberQuery members;
    private final ContactMemberCleanupStore store;

    public CleanupContactMember(ContactMemberQuery members, ContactMemberCleanupStore store) {
        this.members = members;
        this.store = store;
    }

    @Override
    public void remove(long memberId) {
        if (memberId <= 0) {
            throw new ContactMemberCleanupRejectedException(ContactMemberCleanupRejectedException.Reason.LOGIN);
        }
        store.remove(memberId, () -> {
            var member = members.get(memberId);
            if (member.active() || !member.deletionPending()) {
                throw new ContactMemberCleanupRejectedException(ContactMemberCleanupRejectedException.Reason.NOT_PENDING);
            }
        });
    }
}
