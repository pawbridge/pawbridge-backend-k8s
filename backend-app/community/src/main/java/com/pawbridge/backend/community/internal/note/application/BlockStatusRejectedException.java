package com.pawbridge.backend.community.internal.note.application;

public final class BlockStatusRejectedException extends RuntimeException {
    private final Reason reason;

    public BlockStatusRejectedException(Reason reason) {
        super("Note block status request is not allowed.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason { INVALID_MEMBERS, LOGIN, INACTIVE }
}
