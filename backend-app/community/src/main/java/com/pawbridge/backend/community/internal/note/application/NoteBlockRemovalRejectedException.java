package com.pawbridge.backend.community.internal.note.application;

public final class NoteBlockRemovalRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteBlockRemovalRejectedException(Reason reason) {
        super("Private note block removal is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE }
}
