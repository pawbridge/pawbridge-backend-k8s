package com.pawbridge.backend.community.internal.post.application;

import java.io.IOException;
import java.io.InputStream;

public record PostUpload(String originalFilename, String contentType, long size, Content content) {
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }
}
