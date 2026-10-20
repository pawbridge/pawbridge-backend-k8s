package com.pawbridge.backend.user.internal.session.application;

public final class AuthSessionRejectedException extends RuntimeException {
    public enum Reason { INVALID, MISSING, EXPIRED }
    private final Reason reason;
    public AuthSessionRejectedException(Reason reason) {
        super(switch(reason) {
            case INVALID -> "유효하지 않은 토큰입니다.";
            case MISSING -> "리프레시 토큰을 찾을 수 없습니다.";
            case EXPIRED -> "리프레시 토큰이 만료되었습니다.";
        });
        this.reason=reason;
    }
    public Reason reason() { return reason; }
}
