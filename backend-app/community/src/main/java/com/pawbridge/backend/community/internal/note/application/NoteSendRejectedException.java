package com.pawbridge.backend.community.internal.note.application;

public final class NoteSendRejectedException extends RuntimeException {
    public enum Reason {
        LOGIN, INVALID_REQUEST, INVALID_BODY, INVALID_CONTEXT, INACTIVE,
        GONE, CONFLICT, BLOCKED, RATE_LIMIT, NOT_FOUND, REPLY_MISMATCH, CONTEXT_AUTHOR
    }

    private final Reason reason;

    public NoteSendRejectedException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
