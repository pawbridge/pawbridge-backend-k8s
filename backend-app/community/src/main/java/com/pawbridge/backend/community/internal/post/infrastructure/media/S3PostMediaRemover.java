package com.pawbridge.backend.community.internal.post.infrastructure.media;

import com.pawbridge.backend.community.internal.post.application.PostMediaRemover;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** Explicit, best-effort deletion of media URLs read from the post itself. */
public final class S3PostMediaRemover implements PostMediaRemover {
    private static final Logger LOG = LoggerFactory.getLogger(S3PostMediaRemover.class);
    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;
    private final String objectPrefix;

    public S3PostMediaRemover(S3Client s3, String bucket, String publicBaseUrl, String objectPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.publicBaseUrl = removeTrailingSlashes(publicBaseUrl);
        this.objectPrefix = normalizeObjectPrefix(objectPrefix);
    }

    @Override
    public void remove(List<String> imageUrls) {
        if (imageUrls == null) {
            return;
        }
        // Preserve legacy ordering and duplicate attempts. Validation failure is local to each URL.
        for (String url : imageUrls) {
            try {
                String key = objectKey(url);
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception failure) {
                // Do not pass URL, key, SDK text or the original Throwable to the logger.
                LOG.warn("Post media removal failed (exception message omitted): {}", failure.getClass().getName());
            }
        }
    }

    private String objectKey(String url) {
        String publicPrefix = publicBaseUrl + "/";
        if (url == null || !url.startsWith(publicPrefix)) {
            throw new IllegalArgumentException("Unregistered post media URL");
        }
        String key = url.substring(publicPrefix.length());
        if (key.isBlank() || !key.startsWith(objectPrefix) || key.length() == objectPrefix.length()
                || key.contains("\\") || key.contains("%") || key.contains("?") || key.contains("#")
                || key.chars().anyMatch(character -> character < 32 || character == 127)
                || Arrays.stream(key.split("/", -1)).anyMatch(segment ->
                    segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("Post media key outside configured storage path");
        }
        return key;
    }

    private static String normalizeObjectPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return "";
        }
        if (!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")) {
            throw new IllegalArgumentException("Invalid post media object prefix");
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
