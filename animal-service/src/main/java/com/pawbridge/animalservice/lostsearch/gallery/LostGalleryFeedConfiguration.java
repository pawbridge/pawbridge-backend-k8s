package com.pawbridge.animalservice.lostsearch.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lost-gallery-feed.enabled", havingValue = "true")
public class LostGalleryFeedConfiguration {
    @Bean(destroyMethod = "close")
    S3Presigner lostGalleryPresigner(
            @Value("${lost-gallery-feed.storage.endpoint}") URI endpoint,
            @Value("${lost-gallery-feed.storage.access-key-id}") String access,
            @Value("${lost-gallery-feed.storage.secret-access-key}") String secret) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || !endpoint.getHost().matches("[a-z0-9-]+\\.r2\\.cloudflarestorage\\.com")
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                || (endpoint.getPath() != null && !endpoint.getPath().isEmpty() && !"/".equals(endpoint.getPath()))
                || access.isBlank() || secret.isBlank())
            throw new IllegalArgumentException("Dedicated R2 signing configuration is required");
        return S3Presigner.builder().region(Region.of("auto")).endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
    }

    @Bean
    LostGalleryFeed lostGalleryFeed(JdbcTemplate jdbc, ObjectMapper mapper, @Qualifier("lostGalleryPresigner") S3Presigner lostGalleryPresigner) {
        return new LostGalleryFeed(jdbc, mapper, lostGalleryPresigner);
    }
    @Bean(destroyMethod = "close")
    LostGallerySnapshots lostGallerySnapshots(ObjectMapper mapper, LostGalleryFeed feed,
            @Qualifier("lostGalleryPresigner") S3Presigner signer,
            @Value("${lost-gallery-feed.snapshot-directory:/tmp/pawbridge-gallery-snapshots}") String directory) throws java.io.IOException {
        return new LostGallerySnapshots(mapper, feed::streamEntries, signer, java.nio.file.Path.of(directory), java.time.Clock.systemUTC());
    }
}
