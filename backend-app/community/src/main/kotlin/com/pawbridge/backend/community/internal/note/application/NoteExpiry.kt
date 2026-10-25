package com.pawbridge.backend.community.internal.note.application

fun interface NoteExpiry {
    /** Expires one bounded batch and returns only the number of deleted notes. */
    fun expire(): Int
}
