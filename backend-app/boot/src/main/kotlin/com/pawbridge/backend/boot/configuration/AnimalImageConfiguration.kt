package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService
import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler
import com.pawbridge.backend.user.internal.animalcommand.infrastructure.JdbcAnimalCommandActors
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client

/** Opt-in original image routes; borrows the Post-owned storage client and the existing writer for actor lookup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-images", name = ["enabled"], havingValue = "true")
class AnimalImageConfiguration {
    @Bean(destroyMethod = "")
    fun animalImages(@Qualifier("postS3Client") client: S3Client, settings: PostWriteSettings): S3AnimalImages {
        return S3AnimalImages(client, settings.bucket(), settings.publicBaseUrl())
    }

    @Bean
    fun animalImageService(
        images: S3AnimalImages,
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): AnimalImageService {
        if (jdbc.dataSource !== manager.dataSource) {
            throw IllegalArgumentException("Animal image actors must use the writer transaction DataSource")
        }
        val writes = TransactionTemplate(manager)
        writes.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        writes.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        writes.timeout = 10
        // Not a bean: Animal commands already expose the single AnimalCommandActors bean when enabled.
        return AnimalImageService(images, JdbcAnimalCommandActors(jdbc), writes)
    }

    @Bean
    fun animalImageController(images: AnimalImageService): AnimalImageController {
        return AnimalImageController(images)
    }

    @Bean
    fun animalImageExceptionHandler(): AnimalImageExceptionHandler {
        return AnimalImageExceptionHandler()
    }
}
