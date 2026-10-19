package com.pawbridge.backend.community.internal.note.application;

public final class NoteStreamRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteStreamRejectedException(Reason reason) {
        super("Private note stream is not available.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason { LOGIN, INACTIVE }
}
