package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import com.pawbridge.backend.boot.javaIsBlank
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.function.Consumer
import java.util.function.Function
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/** The five existing internal feed endpoints; no gallery publishing, new pool or object transfer. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["lost-gallery-feed.enabled"], havingValue = "true")
class AnimalGalleryFeedConfiguration(
    @Value("\${lost-gallery-feed.internal-api-key:}") internalKey: String,
    @Value("\${lost-gallery-feed.storage.endpoint:}") address: String,
    @Value("\${lost-gallery-feed.storage.access-key-id:}") access: String,
    @Value("\${lost-gallery-feed.storage.secret-access-key:}") secret: String,
    @Value("\${lost-gallery-feed.snapshot-directory:/tmp/pawbridge-gallery-snapshots}") directory: String?,
) {
    private val internalKey: String = requireInternalKey(internalKey)
    private val endpoint: URI = parseEndpoint(address, access, secret)
    private val access: String = access
    private val secret: String = secret
    private val directory: Path = parseDirectory(directory)

    @Bean(destroyMethod = "close")
    fun animalGalleryPresigner(): S3Presigner {
        return S3Presigner.builder()
            .region(Region.of("auto"))
            .endpointOverride(endpoint)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
            .build()
    }

    @Bean
    fun animalGalleryFeed(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("dataSource") source: DataSource,
        @Qualifier("transactionManager") manager: JdbcTransactionManager,
        mapper: ObjectMapper,
        @Qualifier("animalGalleryPresigner") signer: S3Presigner,
    ): LostGalleryFeed {
        if (jdbc.dataSource !== source || manager.dataSource !== source) {
            throw IllegalArgumentException(
                "Gallery reads must use the existing reader DataSource and transaction manager",
            )
        }
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.timeout = 120
        val streaming = TransactionTemplate(manager, reads)
        streaming.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        return LostGalleryFeed(jdbc, mapper, photoSigner(signer), reads, streaming)
    }

    @Bean(destroyMethod = "close")
    @Throws(IOException::class)
    fun animalGallerySnapshots(
        mapper: ObjectMapper,
        feed: LostGalleryFeed,
        @Qualifier("animalGalleryPresigner") signer: S3Presigner,
    ): LostGallerySnapshots {
        val entries = Consumer<Consumer<LostGallerySnapshots.Entry>> { consumer -> feed.streamEntries(consumer) }
        return LostGallerySnapshots(mapper, entries, photoSigner(signer), directory, Clock.systemUTC())
    }

    @Bean
    fun lostGalleryFeedController(feed: LostGalleryFeed): LostGalleryFeedController {
        return LostGalleryFeedController(feed, internalKey)
    }

    @Bean
    fun lostGalleryPagesController(snapshots: LostGallerySnapshots): LostGalleryPagesController {
        return LostGalleryPagesController(snapshots, internalKey)
    }

    private fun photoSigner(signer: S3Presigner): Function<String, String> {
        return Function { key ->
            signer.presignGetObject { request ->
                request.signatureDuration(Duration.ofHours(1))
                    .getObjectRequest { objectRequest -> objectRequest.bucket("pawbridge-animal-originals").key(key) }
            }.url().toString()
        }
    }

    private companion object {
        fun requireInternalKey(internalKey: String): String {
            if (javaIsBlank(internalKey)) {
                throw IllegalArgumentException("Gallery feed authentication is required")
            }
            return internalKey
        }

        fun parseEndpoint(address: String, access: String, secret: String): URI {
            val parsed: URI
            try {
                parsed = URI.create(address)
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("Dedicated R2 signing configuration is required")
            }
            if ("https" != parsed.scheme || parsed.host == null ||
                !Regex("[a-z0-9-]+\\.r2\\.cloudflarestorage\\.com").matches(parsed.host) ||
                parsed.userInfo != null || parsed.query != null || parsed.fragment != null ||
                (parsed.port != -1 && parsed.port != 443) ||
                (parsed.path.isNotEmpty() && "/" != parsed.path) || javaIsBlank(access) || javaIsBlank(secret)
            ) {
                throw IllegalArgumentException("Dedicated R2 signing configuration is required")
            }
            return parsed
        }

        fun parseDirectory(directory: String?): Path {
            try {
                if (directory == null || javaIsBlank(directory)) {
                    throw IllegalArgumentException()
                }
                val selected = Path.of(directory)
                if (!selected.isAbsolute || selected.normalize() != selected || selected.parent == null ||
                    selected == Path.of("").toAbsolutePath().normalize()
                ) {
                    throw IllegalArgumentException()
                }
                return selected
            } catch (invalid: RuntimeException) {
                throw IllegalArgumentException("A local snapshot directory is required")
            }
        }
    }
}
