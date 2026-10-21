package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveHttpClient;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveObjectStorage;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Explicit archive producer; the existing writer and gallery remain separately owned. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "apms-photo-archive", name = "enabled", havingValue = "true")
@EnableConfigurationProperties
@Import(AnimalJpaWriterConfiguration.class)
public class AnimalPhotoArchiveConfiguration {
    static final String GALLERY_BUCKET = "pawbridge-animal-originals";

    @Bean
    @ConfigurationProperties("apms-photo-archive")
    PhotoArchiveProperties apmsPhotoArchiveProperties() { return new PhotoArchiveProperties(); }

    @Bean
    @ConfigurationProperties("apms-photo-archive.storage")
    PhotoArchiveStorageProperties apmsPhotoArchiveStorageProperties() { return new PhotoArchiveStorageProperties(); }

    @Bean
    PhotoArchiveStore apmsPhotoArchiveStore(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeDataSource") HikariDataSource source,
            @Qualifier("animalCommandJpaTransactionManager") JpaTransactionManager manager,
            PhotoArchiveProperties properties, PhotoArchiveStorageProperties storage) {
        properties.validate();
        storage.validate();
        if (!GALLERY_BUCKET.equals(properties.getBucket()))
            throw new IllegalArgumentException("Photo archive must use the existing gallery bucket");
        if (jdbc.getDataSource() != source || manager.getDataSource() != source
                || source.getJdbcUrl() == null || !source.getJdbcUrl().startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("Photo archive requires the same existing PostgreSQL writer");
        return new PhotoArchiveStore(jdbc, manager);
    }

    @Bean(destroyMethod = "close")
    PhotoArchiveHttpClient apmsPhotoArchiveHttp(PhotoArchiveProperties properties, PhotoArchiveStore requiredStore) {
        return new PhotoArchiveHttpClient(properties);
    }

    // No public S3Client bean or default credential chain: this client belongs only to the archive.
    @Bean(destroyMethod = "close")
    PhotoArchiveObjectStorage apmsPhotoArchiveStorage(PhotoArchiveProperties properties,
            PhotoArchiveStorageProperties storage, PhotoArchiveStore requiredStore) {
        var client = S3Client.builder().endpointOverride(storage.getEndpoint())
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        storage.getAccessKeyId(), storage.getSecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build()).build();
        return new PhotoArchiveObjectStorage(client, properties.getBucket());
    }

    @Bean
    PhotoArchiveWorker apmsPhotoArchiveWorker(PhotoArchiveStore store, PhotoArchiveHttpClient http,
            PhotoArchiveObjectStorage storage, PhotoArchiveProperties properties) {
        return new PhotoArchiveWorker(store, http, storage, properties);
    }

    @Bean
    PhotoArchiveSchedule apmsPhotoArchiveSchedule(PhotoArchiveWorker worker,
            PhotoArchiveProperties properties, Environment environment) {
        int seconds = environment.getProperty("apms-photo-archive.shutdown-timeout-seconds", Integer.class, 15);
        return new PhotoArchiveSchedule(worker, properties, seconds);
    }
}
