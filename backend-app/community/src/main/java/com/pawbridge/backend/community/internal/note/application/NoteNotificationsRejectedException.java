package com.pawbridge.backend.community.internal.note.application;

public final class NoteNotificationsRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteNotificationsRejectedException(Reason reason) {
        super("Note notifications request is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE }
}
