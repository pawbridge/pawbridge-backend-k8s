package com.pawbridge.animalservice.photo;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("apms-photo-archive.storage")
public class PhotoArchiveStorageProperties {
    private URI endpoint;
    private String region = "auto";
    private String accessKeyId;
    private String secretAccessKey;

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
