package com.pawbridge.backend.community.internal.note.application;

public final class NoteBlockCreationRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteBlockCreationRejectedException(Reason reason) {
        super("Private note block creation is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, SELF, INACTIVE }
}
