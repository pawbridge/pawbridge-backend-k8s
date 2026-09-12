package com.pawbridge.animalservice.photo;

import java.net.URI;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("apms-photo-archive")
public class PhotoArchiveProperties {
    private String bucket;
    private URI optimizerUrl;
    private String internalApiKey;
    private Set<String> allowedHosts = Set.of("openapi.animal.go.kr");
    private int scanSize = 50;
    private int maxPhotos = 25;
    private int maxRunSeconds = 300;
    private int leaseSeconds = 300;
    private int recheckSeconds = 7 * 24 * 3600;
    private long intervalMs = 900000;
    private long initialDelayMs = 60000;

    public void validate() {
        if (bucket == null || bucket.isBlank() || optimizerUrl == null
                || internalApiKey == null || internalApiKey.isBlank()
                || optimizerUrl.getHost() == null || optimizerUrl.getUserInfo() != null
                || optimizerUrl.getQuery() != null || optimizerUrl.getFragment() != null
                || !Set.of("http", "https").contains(optimizerUrl.getScheme())
                || allowedHosts == null || allowedHosts.isEmpty()
                || scanSize < 1 || scanSize > 500 || maxPhotos < 1 || maxPhotos > 1000
                || maxRunSeconds < 1 || maxRunSeconds > 3600
                || intervalMs < 1000 || intervalMs > 86400000 || initialDelayMs < 0 || initialDelayMs > 86400000
                || leaseSeconds < 300 || leaseSeconds > 3600 || recheckSeconds < 3600) {
            throw new IllegalArgumentException("Invalid APMS photo archive configuration");
        }
    }
}
