package com.pawbridge.backend.community.internal.note.application;

import java.util.List;

public interface NoteBlockStatusReader {
    List<Long> blockedAuthors(long currentUserId, List<Long> distinctMemberIds);
}
