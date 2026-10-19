package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import java.time.Instant;
import java.util.List;

public interface PrivateNoteListReader {
    long count(long currentUserId, String box, boolean favorites, Instant now);

    List<StoredNote> read(long currentUserId, String box, boolean favorites, int page, Instant now);
}
