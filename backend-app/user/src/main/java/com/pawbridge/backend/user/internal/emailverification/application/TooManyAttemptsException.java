package com.pawbridge.backend.user.internal.emailverification.application;

public final class TooManyAttemptsException extends RuntimeException {
    public enum Kind { SEND, VERIFY }

    private final Kind kind;

    public TooManyAttemptsException(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
