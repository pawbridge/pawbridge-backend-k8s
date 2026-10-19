package com.pawbridge.backend.community.internal.comment.application;

/** Verifies active state and ownership, then soft-deletes the comment in one transaction. */
public interface CommentDeletion {
    void delete(long commentId, long authorId);
}
