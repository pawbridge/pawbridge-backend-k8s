package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery
import java.time.Clock

class ListPrivateNotes(
    private val members: ContactMemberQuery,
    private val notes: PrivateNoteListReader,
    private val contactMembers: ContactMembersQuery,
    private val blocks: NoteBlockReader,
    private val clock: Clock,
) {

    fun read(
        currentUserId: Long,
        box: String?,
        favorites: Boolean,
        page: Int,
    ): PrivateNotePage {
        if (page < 0 || page > 10000) {
            throw PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.INVALID_PAGE)
        }
        // java.util.List.of(...).contains(null) throws NullPointerException; kotlin's listOf would answer false.
        if (!java.util.List.of("INBOX", "SENT").contains(box)) {
            throw PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.INVALID_BOX)
        }
        if (currentUserId <= 0) {
            throw PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.LOGIN)
        }
        if (!members.get(currentUserId).active) {
            throw PrivateNoteListRejectedException(PrivateNoteListRejectedException.Reason.INACTIVE)
        }
        val now = clock.instant()
        val count = notes.count(currentUserId, box!!, favorites, now)
        val found = notes.read(currentUserId, box, favorites, page, now)
        val counterpartIds = found.mapNotNull { note -> counterpartId(note) }.distinct()
        val membersById = HashMap<Long?, ContactMember>()
        if (counterpartIds.isNotEmpty()) {
            for (member in contactMembers.getAll(counterpartIds)) {
                membersById[member.userId] = member
            }
        }
        val content = found.map { note ->
            val counterpartId = counterpartId(note)
            // A missing member projection fails here, as the Java original dereferenced null.
            val counterpart = if (counterpartId == null) {
                ContactMember(null, WITHDRAWN_MEMBER, false, false)
            } else {
                membersById[counterpartId]!!
            }
            val contextHref = if (note.contextType == null) {
                null
            } else {
                (if ("POST" == note.contextType) "/community/" else "/reports/") + note.contextId
            }
            val canReply = "INBOX" == note.direction &&
                counterpart.active &&
                !blocks.blocked(note.recipientId!!, counterpart.userId!!)
            PrivateNoteView(
                note.noteId,
                note.body,
                note.direction,
                note.createdAt,
                note.readAt,
                note.favorite,
                if (counterpart.active) counterpart.userId else null,
                if (counterpart.active) counterpart.nickname else WITHDRAWN_MEMBER,
                canReply,
                contextHref,
            )
        }
        return PrivateNotePage(content, count, ((count + PAGE_SIZE - 1) / PAGE_SIZE).toInt(), page, PAGE_SIZE)
    }

    private companion object {
        private const val PAGE_SIZE = 10
        private const val WITHDRAWN_MEMBER = "탈퇴한 회원"

        private fun counterpartId(note: StoredNote): Long? =
            if ("INBOX" == note.direction) note.senderId else note.recipientId
    }
}
