package com.pawbridge.backend.boot.configuration;

import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import javax.crypto.SecretKey;

/** Explicit candidate-only inputs. Values and keys are never rendered in diagnostics. */
public final class PostWriteSettings {
    private final String bucket, publicBaseUrl, objectPrefix, accessKey, secretKey, region;
    private final URI endpoint;
    private final SecretKey jwtKey;

    private PostWriteSettings(Function<String, String> values) {
        try {
            jwtKey = Keys.hmacShaKeyFor(required(values, "PAWBRIDGE_JWT_SECRET").getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) { throw invalid("PAWBRIDGE_JWT_SECRET"); }
        endpoint = address(required(values, "PAWBRIDGE_STORAGE_ENDPOINT"), "PAWBRIDGE_STORAGE_ENDPOINT", true);
        bucket = required(values, "PAWBRIDGE_STORAGE_BUCKET");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) throw invalid("PAWBRIDGE_STORAGE_BUCKET");
        publicBaseUrl = required(values, "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL");
        address(publicBaseUrl, "PAWBRIDGE_STORAGE_PUBLIC_BASE_URL", false);
        objectPrefix = optionalPrefix(values, "PAWBRIDGE_STORAGE_OBJECT_PREFIX");
        if (!objectPrefix.isEmpty() && !objectPrefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")) throw invalid("PAWBRIDGE_STORAGE_OBJECT_PREFIX");
        accessKey = required(values, "PAWBRIDGE_STORAGE_ACCESS_KEY_ID");
        secretKey = required(values, "PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY");
        region = required(values, "PAWBRIDGE_STORAGE_REGION");
    }

    public static PostWriteSettings fromEnvironment(Function<String, String> values) {
        return new PostWriteSettings(values);
    }

    private static String required(Function<String, String> values, String key) {
        String value = values.apply(key);
        if (value == null || value.isBlank()) throw invalid(key);
        return value;
    }

    /** Unset and empty both mean the bucket root. Any other value, including blanks, must match the prefix pattern. */
    private static String optionalPrefix(Function<String, String> values, String key) {
        String value = values.apply(key);
        return value == null ? "" : value;
    }

    private static URI address(String value, String key, boolean endpoint) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || (endpoint && !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))) throw invalid(key);
            return uri;
        } catch (IllegalArgumentException failure) { throw invalid(key); }
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Missing or invalid explicit setting: " + key);
    }

    public SecretKey jwtKey() { return jwtKey; }
    public URI endpoint() { return endpoint; }
    public String bucket() { return bucket; }
    public String publicBaseUrl() { return publicBaseUrl; }
    public String objectPrefix() { return objectPrefix; }
    public String accessKey() { return accessKey; }
    public String secretKey() { return secretKey; }
    public String region() { return region; }
}
