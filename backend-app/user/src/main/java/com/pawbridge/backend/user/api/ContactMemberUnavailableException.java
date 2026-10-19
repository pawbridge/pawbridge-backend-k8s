package com.pawbridge.backend.user.api;

public final class ContactMemberUnavailableException extends RuntimeException {
    public ContactMemberUnavailableException(Throwable cause) {
        super("Contact member lookup is unavailable.", cause);
    }
}
