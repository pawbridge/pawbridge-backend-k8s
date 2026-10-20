package com.pawbridge.backend.community.internal.chat.application;

public final class MemberChatRejectedException extends RuntimeException {
    private final int status;
    public MemberChatRejectedException(int status, String message) {
        super(message);
        this.status=status;
    }
    public int status() { return status; }
}
