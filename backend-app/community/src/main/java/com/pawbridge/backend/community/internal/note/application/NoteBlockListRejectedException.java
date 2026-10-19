package com.pawbridge.backend.community.internal.note.application;

public final class NoteBlockListRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteBlockListRejectedException(Reason reason) {
        super("Note block list request is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { INVALID_PAGE, LOGIN, INACTIVE }
}
