package com.pawbridge.backend.community.internal.post.application;

import com.pawbridge.backend.community.internal.post.domain.PostToCreate;

public interface PostWriter {
    PostSnapshot create(PostToCreate post);
}
