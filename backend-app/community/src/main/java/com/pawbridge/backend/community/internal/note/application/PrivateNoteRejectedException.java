package com.pawbridge.backend.community.internal.note.application;

public final class PrivateNoteRejectedException extends RuntimeException {
    private final Reason reason;

    public PrivateNoteRejectedException(Reason reason) {
        super("Private note request is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE, NOT_FOUND }
}
