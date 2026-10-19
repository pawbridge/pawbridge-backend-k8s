package com.pawbridge.backend.community.internal.note.application;

public final class NoteReadMutationRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteReadMutationRejectedException(Reason reason) {
        super("Private note read mutation is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE, NOT_FOUND }
}
