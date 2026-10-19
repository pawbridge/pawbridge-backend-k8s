package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.BlockPage.BlockView;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ListNoteBlocks {
    private static final int PAGE_SIZE = 10;
    private final ContactMemberQuery members;
    private final NoteBlockListReader blocks;
    private final ContactMembersQuery contactMembers;

    public ListNoteBlocks(ContactMemberQuery members, NoteBlockListReader blocks, ContactMembersQuery contactMembers) {
        this.members = members;
        this.blocks = blocks;
        this.contactMembers = contactMembers;
    }

    public BlockPage read(long currentUserId, int page) {
        if (page < 0 || page > 10000) {
            throw new NoteBlockListRejectedException(NoteBlockListRejectedException.Reason.INVALID_PAGE);
        }
        if (currentUserId <= 0) {
            throw new NoteBlockListRejectedException(NoteBlockListRejectedException.Reason.LOGIN);
        }
        if (!members.get(currentUserId).active()) {
            throw new NoteBlockListRejectedException(NoteBlockListRejectedException.Reason.INACTIVE);
        }
        long count = blocks.count(currentUserId);
        List<NoteBlockListReader.Block> pageBlocks = blocks.read(currentUserId, page);
        Map<Long, ContactMember> membersById = new HashMap<>();
        if (!pageBlocks.isEmpty()) {
            for (ContactMember member : contactMembers.getAll(pageBlocks.stream()
                    .map(NoteBlockListReader.Block::memberId).toList())) {
                membersById.put(member.userId(), member);
            }
        }
        List<BlockView> content = pageBlocks.stream().map(block -> {
            ContactMember member = membersById.get(block.memberId());
            return new BlockView(block.memberId(), member.active() ? member.nickname() : "탈퇴한 회원", block.createdAt());
        }).toList();
        return new BlockPage(content, count, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE), page);
    }
}
