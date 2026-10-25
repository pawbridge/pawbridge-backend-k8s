package com.pawbridge.backend.community.internal.note.application

fun interface NoteChangeHints {
    fun resync(memberId: Long)
}
