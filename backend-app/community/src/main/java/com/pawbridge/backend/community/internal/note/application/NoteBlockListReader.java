package com.pawbridge.backend.community.internal.note.application;

import java.time.Instant;
import java.util.List;

public interface NoteBlockListReader {
    long count(long currentUserId);

    List<Block> read(long currentUserId, int page);

    record Block(long memberId, Instant createdAt) {
    }
}
