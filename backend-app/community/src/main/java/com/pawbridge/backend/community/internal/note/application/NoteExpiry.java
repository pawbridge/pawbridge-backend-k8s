package com.pawbridge.backend.community.internal.note.application;

@FunctionalInterface
public interface NoteExpiry {
    /** Expires one bounded batch and returns only the number of deleted notes. */
    int expire();
}
