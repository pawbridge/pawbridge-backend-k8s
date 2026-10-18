package com.pawbridge.backend.community.internal.post.infrastructure.media;

import com.pawbridge.backend.community.internal.post.application.PostMediaUploader;
import com.pawbridge.backend.community.internal.post.application.PostUpload;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3-compatible adapter for the legacy general-post upload contract. */
public final class S3PostMediaUploader implements PostMediaUploader {
    private static final List<String> IMAGE_TYPES = List.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp");
    private static final List<String> VIDEO_TYPES = List.of(
            "video/mp4", "video/quicktime", "video/x-msvideo", "video/x-matroska");

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;
    private final String objectPrefix;

    public S3PostMediaUploader(S3Client s3, String bucket, String publicBaseUrl, String objectPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.publicBaseUrl = removeTrailingSlashes(publicBaseUrl);
        this.objectPrefix = normalizeObjectPrefix(objectPrefix);
    }

    @Override
    public List<String> upload(List<PostUpload> uploads) {
        List<String> urls = new ArrayList<>();
        if (uploads == null || uploads.isEmpty()) {
            return urls;
        }
        for (PostUpload upload : uploads) {
            if (upload.size() == 0) {
                continue;
            }
            try {
                urls.add(uploadSingleFile(upload));
            } catch (IOException | RuntimeException failure) {
                // General posts historically leave earlier objects in place after a failure.
                throw new RuntimeException("파일 업로드 중 오류가 발생했습니다", failure);
            }
        }
        return urls;
    }

    private String uploadSingleFile(PostUpload upload) throws IOException {
        validateMedia(upload);
        String originalFilename = upload.originalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.')) : "";
        String folder = VIDEO_TYPES.contains(upload.contentType().toLowerCase())
                ? "posts/videos/" : "posts/images/";
        String key = objectPrefix + folder + UUID.randomUUID() + extension;
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(upload.contentType())
                .build();
        try (ReopeningContent content = new ReopeningContent(upload.content())) {
            s3.putObject(request, RequestBody.fromContentProvider(content, upload.size(), upload.contentType()));
        }
        return publicBaseUrl + "/" + key;
    }

    /** SDK signing and sending may request separate streams; multipart disk streams cannot reset. */
    private static final class ReopeningContent implements ContentStreamProvider, AutoCloseable {
        private final PostUpload.Content source;
        private InputStream current;
        private boolean first = true;

        private ReopeningContent(PostUpload.Content source) throws IOException {
            this.source = source;
            current = source.open();
        }

        @Override
        public InputStream newStream() {
            if (first) {
                first = false;
                return current;
            }
            try {
                close();
                current = source.open();
                return current;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void close() throws IOException {
            InputStream closing = current;
            current = null;
            if (closing != null) closing.close();
        }
    }

    private static void validateMedia(PostUpload upload) {
        String contentType = upload.contentType();
        if (contentType == null) {
            throw new IllegalArgumentException(String.format(
                    "파일 타입을 확인할 수 없습니다. (파일: %s)", upload.originalFilename()));
        }
        String lowerContentType = contentType.toLowerCase();
        if (!IMAGE_TYPES.contains(lowerContentType) && !VIDEO_TYPES.contains(lowerContentType)) {
            throw new IllegalArgumentException(String.format(
                    "지원하지 않는 파일 형식입니다. (업로드한 파일: %s, 타입: %s). "
                            + "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv",
                    upload.originalFilename(), contentType));
        }
    }

    private static String normalizeObjectPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return "";
        }
        if (!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")) {
            throw new IllegalArgumentException(
                    "저장 경로는 영문, 숫자, 하이픈, 밑줄과 경로 구분자로 구성해야 합니다.");
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String removeTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
