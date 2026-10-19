package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.util.List;

public final class ReadBlockedAuthors {
    private final ContactMemberQuery members;
    private final NoteBlockStatusReader blocks;

    public ReadBlockedAuthors(ContactMemberQuery members, NoteBlockStatusReader blocks) {
        this.members = members;
        this.blocks = blocks;
    }

    public BlockedAuthors read(long currentUserId, List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty() || memberIds.size() > 100
                || memberIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BlockStatusRejectedException(BlockStatusRejectedException.Reason.INVALID_MEMBERS);
        }
        if (currentUserId <= 0) {
            throw new BlockStatusRejectedException(BlockStatusRejectedException.Reason.LOGIN);
        }
        if (!members.get(currentUserId).active()) {
            throw new BlockStatusRejectedException(BlockStatusRejectedException.Reason.INACTIVE);
        }
        return new BlockedAuthors(blocks.blockedAuthors(currentUserId, memberIds.stream().distinct().toList()));
    }
}
