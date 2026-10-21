package com.pawbridge.backend.boot.configuration;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("apms-photo-archive.storage")
public class PhotoArchiveStorageProperties {
    private URI endpoint;
    private String region = "auto";
    private String accessKeyId;
    private String secretAccessKey;


    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }

    public void validate() {
        if (endpoint == null || endpoint.getHost() == null
                || !"https".equals(endpoint.getScheme()) && !"http".equals(endpoint.getScheme())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || region == null || region.isBlank()
                || accessKeyId == null || accessKeyId.isBlank()
                || secretAccessKey == null || secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("Dedicated APMS photo storage configuration is required");
        }
    }
}
