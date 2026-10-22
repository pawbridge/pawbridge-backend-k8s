package com.pawbridge.backend.user.internal.emailverification.application;

public final class EmailTemplateLoadException extends RuntimeException {
    public EmailTemplateLoadException() {
        super();
    }

    public EmailTemplateLoadException(Throwable cause) {
        super(null, cause);
    }
}
