package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

public interface PostSearchReader {
    List<PostSnapshot> search(String keyword);
}
