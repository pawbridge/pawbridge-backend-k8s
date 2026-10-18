package com.pawbridge.backend.community.internal.post.application;

import java.util.List;

public interface PostMediaUploader {
    List<String> upload(List<PostUpload> files);
}
