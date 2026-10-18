package com.pawbridge.backend.community.internal.post.application;

import java.util.Optional;

public interface PostReader {
    Optional<PostSnapshot> findById(long postId);
}
