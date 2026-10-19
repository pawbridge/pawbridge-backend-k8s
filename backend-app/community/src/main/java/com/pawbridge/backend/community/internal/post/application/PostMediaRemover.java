package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

/** Attempts each stored media URL independently; individual failures do not abort deletion. */
public interface PostMediaRemover {
    void remove(List<String> imageUrls);
}
