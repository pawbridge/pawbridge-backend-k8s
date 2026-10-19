package com.pawbridge.backend.community.internal.comment.domain;

public final class CommentOwnership {
    private CommentOwnership() {
    }

    public static void requireAuthor(long storedAuthorId, long requestingAuthorId) {
        if (storedAuthorId != requestingAuthorId) {
            throw new CommentNotOwnedException();
        }
    }
}
