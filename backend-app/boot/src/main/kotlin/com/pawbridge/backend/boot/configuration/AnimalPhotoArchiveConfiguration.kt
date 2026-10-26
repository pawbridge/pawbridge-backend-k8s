package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveHttpClient
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveObjectStorage
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

/** Explicit archive producer; the existing writer and gallery remain separately owned. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "apms-photo-archive", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties
@Import(AnimalJpaWriterConfiguration::class)
class AnimalPhotoArchiveConfiguration {
    @Bean
    @ConfigurationProperties("apms-photo-archive")
    fun apmsPhotoArchiveProperties(): PhotoArchiveProperties {
        return PhotoArchiveProperties()
    }

    @Bean
    @ConfigurationProperties("apms-photo-archive.storage")
    fun apmsPhotoArchiveStorageProperties(): PhotoArchiveStorageProperties {
        return PhotoArchiveStorageProperties()
    }

    @Bean
    fun apmsPhotoArchiveStore(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeDataSource") source: HikariDataSource,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        properties: PhotoArchiveProperties,
        storage: PhotoArchiveStorageProperties,
    ): PhotoArchiveStore {
        properties.validate()
        storage.validate()
        if (GALLERY_BUCKET != properties.bucket) {
            throw IllegalArgumentException("Photo archive must use the existing gallery bucket")
        }
        if (jdbc.dataSource !== source || manager.dataSource !== source ||
            source.jdbcUrl == null || !source.jdbcUrl.startsWith("jdbc:postgresql://")
        ) {
            throw IllegalArgumentException("Photo archive requires the same existing PostgreSQL writer")
        }
        return PhotoArchiveStore(jdbc, manager)
    }

    @Bean(destroyMethod = "close")
    fun apmsPhotoArchiveHttp(
        properties: PhotoArchiveProperties,
        requiredStore: PhotoArchiveStore,
    ): PhotoArchiveHttpClient {
        return PhotoArchiveHttpClient(properties)
    }

    // No public S3Client bean or default credential chain: this client belongs only to the archive.
    @Bean(destroyMethod = "close")
    fun apmsPhotoArchiveStorage(
        properties: PhotoArchiveProperties,
        storage: PhotoArchiveStorageProperties,
        requiredStore: PhotoArchiveStore,
    ): PhotoArchiveObjectStorage {
        val client = S3Client.builder()
            .endpointOverride(storage.endpoint)
            .region(Region.of(storage.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(storage.accessKeyId, storage.secretAccessKey),
                ),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build(),
            )
            .build()
        return PhotoArchiveObjectStorage(client, properties.bucket)
    }

    @Bean
    fun apmsPhotoArchiveWorker(
        store: PhotoArchiveStore,
        http: PhotoArchiveHttpClient,
        storage: PhotoArchiveObjectStorage,
        properties: PhotoArchiveProperties,
    ): PhotoArchiveWorker {
        return PhotoArchiveWorker(store, http, storage, properties)
    }

    @Bean
    fun apmsPhotoArchiveSchedule(
        worker: PhotoArchiveWorker,
        properties: PhotoArchiveProperties,
        environment: Environment,
    ): PhotoArchiveSchedule {
        val seconds = environment.getProperty(
            "apms-photo-archive.shutdown-timeout-seconds",
            Int::class.javaObjectType,
            15,
        )
        return PhotoArchiveSchedule(worker, properties, seconds)
    }

    companion object {
        const val GALLERY_BUCKET = "pawbridge-animal-originals"
    }
}
