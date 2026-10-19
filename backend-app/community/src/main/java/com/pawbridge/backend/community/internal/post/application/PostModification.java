package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

/** Recheck ownership and commit post, event and projection together; null fields retain the locked row's values. */
@FunctionalInterface
public interface PostModification {
    PostSnapshot update(long postId, long authorId, String title, String content, List<String> imageUrls);
}
