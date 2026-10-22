package com.pawbridge.backend.user.internal.emailverification.application;

public final class ExpiredCodeException extends RuntimeException {
    public ExpiredCodeException() {
        super();
    }

    public ExpiredCodeException(Throwable cause) {
        super(null, cause);
    }
}
