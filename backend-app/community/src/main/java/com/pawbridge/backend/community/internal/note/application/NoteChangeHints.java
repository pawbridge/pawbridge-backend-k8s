package com.pawbridge.backend.community.internal.note.application;

@FunctionalInterface
public interface NoteChangeHints {
    void resync(long memberId);
}
