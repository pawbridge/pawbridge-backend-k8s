package com.pawbridge.backend.community.internal.post.domain;

public enum PostBoardType {
    MISSING,
    PROTECTION,
    REPORT,
    ADOPTION,
    COMMUNICATION;

    public void requireGeneralPost() {
        if (this == MISSING || this == REPORT) {
            throw new PostBoardNotAllowedException();
        }
    }
}
