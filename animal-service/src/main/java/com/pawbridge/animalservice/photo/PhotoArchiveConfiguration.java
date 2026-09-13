package com.pawbridge.animalservice.photo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** No archive beans, table access or external requests before explicit activation. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="apms-photo-archive",name="enabled",havingValue="true")
@EnableConfigurationProperties({PhotoArchiveProperties.class, PhotoArchiveStorageProperties.class})
public class PhotoArchiveConfiguration {
    @Bean
    PhotoArchiveSchedule photoArchiveSchedule(PhotoArchiveWorker worker, PhotoArchiveProperties properties) {
        return new PhotoArchiveSchedule(worker, properties);
    }
    // Keep this client inside the archive adapter: publishing another S3Client bean
    // would interfere with the public-upload client's AWS auto-configuration.
    @Bean(destroyMethod="close")
    PhotoArchiveObjectStorage photoArchiveObjectStorage(PhotoArchiveProperties properties,
                                                        PhotoArchiveStorageProperties storage) {
        properties.validate();
        storage.validate();
        S3Client client = S3Client.builder()
                .endpointOverride(storage.getEndpoint())
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        storage.getAccessKeyId(), storage.getSecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .build();
        return new PhotoArchiveObjectStorage(client, properties.getBucket());
    }

    @Bean
    PhotoArchiveWorker photoArchiveWorker(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                         PhotoArchiveObjectStorage storage, PhotoArchiveProperties properties) {
        return new PhotoArchiveWorker(new PhotoArchiveStore(jdbc, manager), new PhotoArchiveHttpClient(properties),
                storage, properties);
    }
}
