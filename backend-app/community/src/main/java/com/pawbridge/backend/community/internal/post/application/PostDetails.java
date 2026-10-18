package com.pawbridge.backend.community.internal.post.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record PostDetails(
        long postId,
        long authorId,
        String authorNickname,
        String title,
        String content,
        String boardType,
        List<String> imageUrls,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public PostDetails {
        if (imageUrls != null) {
            imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
        }
    }
}
