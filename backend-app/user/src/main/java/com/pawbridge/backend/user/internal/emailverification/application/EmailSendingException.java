package com.pawbridge.backend.user.internal.emailverification.application;

public final class EmailSendingException extends RuntimeException {
    public EmailSendingException() {
        super();
    }

    public EmailSendingException(Throwable cause) {
        super(null, cause);
    }
}
