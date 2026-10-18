package com.pawbridge.backend.boot.configuration;

import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import javax.crypto.SecretKey;

/** Explicit candidate-only inputs. Values and keys are never rendered in diagnostics. */
public final class PostWriteSettings {
    private final String username, password, bucket, publicBaseUrl, objectPrefix, accessKey, secretKey, region;
    private final URI endpoint;
    private final SecretKey jwtKey;

    private PostWriteSettings(Function<String, String> values, ReadDatabaseSettings reader) {
        username = required(values, "F3_DB_WRITER_USER");
        if (username.equals(reader.username())) throw invalid("F3_DB_WRITER_USER");
        password = required(values, "F3_DB_WRITER_PASSWORD");
        try {
            jwtKey = Keys.hmacShaKeyFor(required(values, "F3_JWT_SECRET").getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) { throw invalid("F3_JWT_SECRET"); }
        endpoint = address(required(values, "F3_STORAGE_ENDPOINT"), "F3_STORAGE_ENDPOINT", true);
        bucket = required(values, "F3_STORAGE_BUCKET");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) throw invalid("F3_STORAGE_BUCKET");
        publicBaseUrl = required(values, "F3_STORAGE_PUBLIC_BASE_URL");
        address(publicBaseUrl, "F3_STORAGE_PUBLIC_BASE_URL", false);
        objectPrefix = required(values, "F3_STORAGE_OBJECT_PREFIX");
        if (!objectPrefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")) throw invalid("F3_STORAGE_OBJECT_PREFIX");
        accessKey = required(values, "F3_STORAGE_ACCESS_KEY_ID");
        secretKey = required(values, "F3_STORAGE_SECRET_ACCESS_KEY");
        region = required(values, "F3_STORAGE_REGION");
    }

    public static PostWriteSettings fromEnvironment(Function<String, String> values, ReadDatabaseSettings reader) {
        return new PostWriteSettings(values, reader);
    }

    private static String required(Function<String, String> values, String key) {
        String value = values.apply(key);
        if (value == null || value.isBlank()) throw invalid(key);
        return value;
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

    public String username() { return username; }
    public String password() { return password; }
    public SecretKey jwtKey() { return jwtKey; }
    public URI endpoint() { return endpoint; }
    public String bucket() { return bucket; }
    public String publicBaseUrl() { return publicBaseUrl; }
    public String objectPrefix() { return objectPrefix; }
    public String accessKey() { return accessKey; }
    public String secretKey() { return secretKey; }
    public String region() { return region; }
}
