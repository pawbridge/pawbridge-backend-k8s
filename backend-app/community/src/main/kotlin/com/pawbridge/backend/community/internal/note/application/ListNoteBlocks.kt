package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.BlockPage.BlockView
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery

class ListNoteBlocks(
    private val members: ContactMemberQuery,
    private val blocks: NoteBlockListReader,
    private val contactMembers: ContactMembersQuery,
) {

    fun read(
        currentUserId: Long,
        page: Int,
    ): BlockPage {
        if (page < 0 || page > 10000) {
            throw NoteBlockListRejectedException(NoteBlockListRejectedException.Reason.INVALID_PAGE)
        }
        if (currentUserId <= 0) {
            throw NoteBlockListRejectedException(NoteBlockListRejectedException.Reason.LOGIN)
        }
        if (!members.get(currentUserId).active) {
            throw NoteBlockListRejectedException(NoteBlockListRejectedException.Reason.INACTIVE)
        }
        val count = blocks.count(currentUserId)
        val pageBlocks = blocks.read(currentUserId, page)
        val membersById = HashMap<Long?, ContactMember>()
        if (pageBlocks.isNotEmpty()) {
            for (member in contactMembers.getAll(pageBlocks.map { block -> block.memberId })) {
                membersById[member.userId] = member
            }
        }
        val content = pageBlocks.map { block ->
            // A missing member projection fails here, as the Java original dereferenced null.
            val member = membersById[block.memberId]!!
            BlockView(block.memberId, if (member.active) member.nickname else WITHDRAWN_MEMBER, block.createdAt)
        }
        return BlockPage(content, count, ((count + PAGE_SIZE - 1) / PAGE_SIZE).toInt(), page)
    }

    private companion object {
        private const val PAGE_SIZE = 10
        private const val WITHDRAWN_MEMBER = "탈퇴한 회원"
    }
}
