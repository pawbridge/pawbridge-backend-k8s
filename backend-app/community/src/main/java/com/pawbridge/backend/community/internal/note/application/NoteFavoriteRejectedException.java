package com.pawbridge.backend.community.internal.note.application;

public final class NoteFavoriteRejectedException extends RuntimeException {
    private final Reason reason;

    public NoteFavoriteRejectedException(Reason reason) {
        super("Private note favorite mutation is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { LOGIN, INACTIVE, NOT_FOUND }
}
