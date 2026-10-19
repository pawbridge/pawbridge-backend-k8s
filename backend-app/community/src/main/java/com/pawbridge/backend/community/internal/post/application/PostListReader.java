package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

public interface PostListReader {
    List<PostSnapshot> findAll();
}
