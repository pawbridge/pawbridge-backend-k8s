package com.pawbridge.backend.community.internal.note.application;

public interface NoteBlockReader {
    boolean blocked(long firstMemberId, long secondMemberId);
}
