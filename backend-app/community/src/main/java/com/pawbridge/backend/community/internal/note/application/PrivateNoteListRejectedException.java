package com.pawbridge.backend.community.internal.note.application;

public final class PrivateNoteListRejectedException extends RuntimeException {
    private final Reason reason;

    public PrivateNoteListRejectedException(Reason reason) {
        super("Private note list request is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { INVALID_PAGE, INVALID_BOX, LOGIN, INACTIVE }
}
