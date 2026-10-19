package com.pawbridge.backend.community.internal.note.application;

public final class RecipientRejectedException extends RuntimeException {
    private final Reason reason;

    public RecipientRejectedException(Reason reason) {
        super("Note recipient is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE, BLOCKED }
}
