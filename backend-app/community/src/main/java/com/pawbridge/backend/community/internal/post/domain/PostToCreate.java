package com.pawbridge.backend.community.internal.post.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record PostToCreate(long authorId, String title, String content,
                           PostBoardType boardType, List<String> imageUrls) {
    public PostToCreate {
        imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
    }
}
