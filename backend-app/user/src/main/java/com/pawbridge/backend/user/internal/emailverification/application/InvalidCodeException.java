package com.pawbridge.backend.user.internal.emailverification.application;

public final class InvalidCodeException extends RuntimeException {
    public InvalidCodeException() {
        super();
    }

    public InvalidCodeException(Throwable cause) {
        super(null, cause);
    }
}
