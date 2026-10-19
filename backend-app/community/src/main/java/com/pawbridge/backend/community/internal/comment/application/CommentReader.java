package com.pawbridge.backend.community.internal.comment.application;

import java.util.Optional;

public interface CommentReader {
    Optional<CommentSnapshot> findById(long commentId);
}
