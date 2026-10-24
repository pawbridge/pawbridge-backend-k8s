package com.pawbridge.backend.user.internal.session.application;

public final class AuthSessionRejectedException extends RuntimeException {

    public enum Reason {
        /** No refresh cookie was sent. */
        MISSING,
        /** Unknown, expired or revoked token. */
        INVALID,
        /** A token that was already rotated away was presented again; its session has been revoked. */
        REUSED
    }

    private final Reason reason;

    public AuthSessionRejectedException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    // Reuse gets the same message as an invalid token so the response does not confirm a stolen token.
    private static String message(Reason reason) {
        if (reason == Reason.MISSING) {
            return "리프레시 토큰을 찾을 수 없습니다.";
        }
        return "유효하지 않은 토큰입니다.";
    }
}
