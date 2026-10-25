package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import java.time.Instant

interface PrivateNoteListReader {
    fun count(
        currentUserId: Long,
        box: String,
        favorites: Boolean,
        now: Instant,
    ): Long

    fun read(
        currentUserId: Long,
        box: String,
        favorites: Boolean,
        page: Int,
        now: Instant,
    ): List<StoredNote>
}
