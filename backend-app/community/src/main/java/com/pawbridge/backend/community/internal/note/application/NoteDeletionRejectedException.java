package com.pawbridge.backend.community.internal.note.application;

public final class NoteDeletionRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteDeletionRejectedException(Reason reason) {
        super("Private note deletion is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE, NOT_FOUND }
}
