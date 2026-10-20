package com.pawbridge.backend.community.internal.note.application;

public final class ContactMemberCleanupRejectedException extends RuntimeException {
    public enum Reason { LOGIN, NOT_PENDING }

    private final Reason reason;

    public ContactMemberCleanupRejectedException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
