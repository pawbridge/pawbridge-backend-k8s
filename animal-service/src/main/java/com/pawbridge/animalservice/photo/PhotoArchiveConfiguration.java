package com.pawbridge.animalservice.photo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/** No archive beans, table access or external requests before explicit activation. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="apms-photo-archive",name="enabled",havingValue="true")
@EnableConfigurationProperties(PhotoArchiveProperties.class)
public class PhotoArchiveConfiguration {
    @Bean
    PhotoArchiveSchedule photoArchiveSchedule(PhotoArchiveWorker worker, PhotoArchiveProperties properties) {
        return new PhotoArchiveSchedule(worker, properties);
    }
    @Bean
    PhotoArchiveWorker photoArchiveWorker(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                         S3Client s3, PhotoArchiveProperties properties) {
        properties.validate();
        return new PhotoArchiveWorker(new PhotoArchiveStore(jdbc, manager), new PhotoArchiveHttpClient(properties),
                new PhotoArchiveObjectStorage(s3, properties.getBucket()), properties);
    }
}
