package com.pawbridge.backend.community.internal.post.application;

/** Rechecks active state and ownership while atomically deleting the post's DB projections. */
public interface PostDeletion {
    void delete(long postId, long authorId);
}
