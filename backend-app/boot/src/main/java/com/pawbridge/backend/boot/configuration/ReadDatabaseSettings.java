package com.pawbridge.backend.boot.configuration;

import java.net.URI;
import java.util.function.Function;

/** Explicit candidate inputs; there is no production address or credential fallback. */
public final class ReadDatabaseSettings {
    /** Single shared pool for reads and writes; PAWBRIDGE_DB_POOL_MAX_SIZE overrides within 1..50. */
    public static final int DEFAULT_POOL_MAX_SIZE = 10;
    public static final int MAX_POOL_MAX_SIZE = 50;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int maximumPoolSize;

    private ReadDatabaseSettings(String jdbcUrl, String username, String password, int maximumPoolSize) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maximumPoolSize = maximumPoolSize;
    }

    public static ReadDatabaseSettings fromEnvironment(Function<String, String> values) {
        String url = required(values, "PAWBRIDGE_DB_URL");
        validateUrl(url);
        String username = required(values, "PAWBRIDGE_DB_USER");
        String password = required(values, "PAWBRIDGE_DB_PASSWORD");
        String requestedSize = values.apply("PAWBRIDGE_DB_POOL_MAX_SIZE");
        int maximum = DEFAULT_POOL_MAX_SIZE;
        if (requestedSize != null) {
            try { maximum = Integer.parseInt(requestedSize); }
            catch (NumberFormatException ignored) { throw invalid("PAWBRIDGE_DB_POOL_MAX_SIZE"); }
        }
        if (maximum < 1 || maximum > MAX_POOL_MAX_SIZE) { throw invalid("PAWBRIDGE_DB_POOL_MAX_SIZE"); }
        return new ReadDatabaseSettings(url, username, password, maximum);
    }

    private static String required(Function<String, String> values, String key) {
        String value = values.apply(key);
        if (value == null || value.isBlank()) { throw invalid(key); }
        return value;
    }

    private static void validateUrl(String value) {
        try {
            if (!value.startsWith("jdbc:postgresql://")) { throw new IllegalArgumentException(); }
            URI uri = URI.create(value.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getFragment() != null || uri.getPath() == null
                    || !uri.getPath().matches("/[a-zA-Z0-9_]+") || uri.getPort() == 0
                    || uri.getPort() > 65535) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException ignored) { throw invalid("PAWBRIDGE_DB_URL"); }
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Missing or invalid explicit setting: " + key);
    }

    public String jdbcUrl() { return jdbcUrl; }
    public String username() { return username; }
    public String password() { return password; }
    public int maximumPoolSize() { return maximumPoolSize; }
}
