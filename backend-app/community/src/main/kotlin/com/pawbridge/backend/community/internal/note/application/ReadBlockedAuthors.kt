package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMemberQuery

class ReadBlockedAuthors(
    private val members: ContactMemberQuery,
    private val blocks: NoteBlockStatusReader,
) {

    fun read(
        currentUserId: Long,
        memberIds: List<Long?>?,
    ): BlockedAuthors {
        if (memberIds == null ||
            memberIds.isEmpty() ||
            memberIds.size > 100 ||
            memberIds.any { id -> id == null || id <= 0 }
        ) {
            throw BlockStatusRejectedException(BlockStatusRejectedException.Reason.INVALID_MEMBERS)
        }
        if (currentUserId <= 0) {
            throw BlockStatusRejectedException(BlockStatusRejectedException.Reason.LOGIN)
        }
        if (!members.get(currentUserId).active) {
            throw BlockStatusRejectedException(BlockStatusRejectedException.Reason.INACTIVE)
        }
        // Every element is non-null here: null IDs were rejected above.
        return BlockedAuthors(blocks.blockedAuthors(currentUserId, memberIds.filterNotNull().distinct()))
    }
}
