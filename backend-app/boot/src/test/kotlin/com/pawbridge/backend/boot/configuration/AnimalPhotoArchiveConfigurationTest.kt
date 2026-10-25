package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.PawBridgeApplication
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveHttpClient
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveObjectStorage
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

class AnimalPhotoArchiveConfigurationTest {
    private val context = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
        .withUserConfiguration(AnimalPhotoArchiveConfiguration::class.java)

    @Test
    fun givenActualMainAssembly__whenImportsAreRead__thenPhotoProducerIsRegistered() {
        assertThat(PawBridgeApplication::class.java.getAnnotation(Import::class.java).value.map { it.java })
            .contains(AnimalPhotoArchiveConfiguration::class.java)
    }

    @Test
    fun givenMissingFlag__whenContextStarts__thenNoWriterOrExternalClientsAreRequired() {
        context.withPropertyValues("apms-photo-archive.optimizer-url=invalid").run { app ->
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(PhotoArchiveWorker::class.java)
                .doesNotHaveBean(PhotoArchiveSchedule::class.java)
                .doesNotHaveBean(PhotoArchiveHttpClient::class.java)
                .doesNotHaveBean(PhotoArchiveObjectStorage::class.java)
                .doesNotHaveBean(JpaTransactionManager::class.java)
        }
    }

    @Test
    fun givenExplicitOff__whenContextStarts__thenNoArchiveBeansAreCreated() {
        context.withPropertyValues("apms-photo-archive.enabled=false").run { app ->
            assertThat(app).hasNotFailed().doesNotHaveBean(PhotoArchiveStore::class.java)
        }
    }

    @Test
    fun givenExplicitSettings__whenComposed__thenShareWriterAndCreateNoPublicS3ClientOrConnection() {
        enabled().run { app ->
            assertThat(app).hasNotFailed()
                .hasSingleBean(PhotoArchiveWorker::class.java)
                .hasSingleBean(PhotoArchiveSchedule::class.java)
                .hasSingleBean(HikariDataSource::class.java)
                .hasSingleBean(JpaTransactionManager::class.java)
                .doesNotHaveBean(S3Client::class.java)
                .doesNotHaveBean("apmsAnimalSyncJob")
                .doesNotHaveBean("petTravelCollectionJob")
            val manager = app.getBean("animalCommandJpaTransactionManager", JpaTransactionManager::class.java)
            val transactions =
                ReflectionTestUtils.getField(app.getBean(PhotoArchiveStore::class.java), "transaction") as TransactionTemplate
            assertThat(transactions.transactionManager).isSameAs(manager)
            assertThat(transactions.timeout).isEqualTo(15)
            assertThat(app.getBean(PhotoArchiveProperties::class.java).bucket).isEqualTo("pawbridge-animal-originals")
            assertThat(app.getBean(PhotoArchiveHttpClient::class.java)).isInstanceOf(AutoCloseable::class.java)
            verify(app.getBean(HikariDataSource::class.java), never()).getConnection()
        }
    }

    @Test
    fun givenDifferentBucket__whenEnabled__thenRejectBeforeCreatingArchiveClients() {
        enabled().withPropertyValues("apms-photo-archive.bucket=some-other-bucket").run { app ->
            assertThat(app.startupFailure).hasRootCauseMessage("Photo archive must use the existing gallery bucket")
        }
    }

    @Test
    fun givenOnlyPublicUploadCredentials__whenEnabled__thenDedicatedCredentialsRemainRequired() {
        enabled().withPropertyValues(
            "apms-photo-archive.storage.access-key-id=",
            "spring.cloud.aws.credentials.access-key=public-fixture",
            "spring.cloud.aws.credentials.secret-key=public-fixture",
        ).run { app ->
            assertThat(app.startupFailure).hasRootCauseMessage("Dedicated APMS photo storage configuration is required")
        }
    }

    @Test
    fun givenMixedWriter__whenCreatingStore__thenFailBeforeBorrowingConnection() {
        val source = mock(HikariDataSource::class.java)
        whenever(source.jdbcUrl).thenReturn("jdbc:postgresql://127.0.0.1:1/synthetic")
        val manager = mock(JpaTransactionManager::class.java)
        whenever(manager.dataSource).thenReturn(source)
        assertThatThrownBy {
            AnimalPhotoArchiveConfiguration().apmsPhotoArchiveStore(
                JdbcTemplate(mock(HikariDataSource::class.java)), source, manager, properties(), storage(),
            )
        }.hasMessage("Photo archive requires the same existing PostgreSQL writer")
    }

    private fun enabled(): ApplicationContextRunner {
        return context.withUserConfiguration(WriterFixture::class.java).withPropertyValues(
            "apms-photo-archive.enabled=true",
            "apms-photo-archive.bucket=pawbridge-animal-originals",
            "apms-photo-archive.optimizer-url=http://127.0.0.1:1/optimize",
            "apms-photo-archive.internal-api-key=synthetic-key",
            "apms-photo-archive.storage.endpoint=http://127.0.0.1:1",
            "apms-photo-archive.storage.access-key-id=synthetic-access",
            "apms-photo-archive.storage.secret-access-key=synthetic-secret",
            "apms-photo-archive.initial-delay-ms=3600000",
        )
    }

    private fun properties(): PhotoArchiveProperties {
        val properties = PhotoArchiveProperties()
        properties.bucket = "pawbridge-animal-originals"
        properties.optimizerUrl = URI.create("http://127.0.0.1:1/optimize")
        properties.internalApiKey = "synthetic-key"
        return properties
    }

    private fun storage(): PhotoArchiveStorageProperties {
        val storage = PhotoArchiveStorageProperties()
        storage.endpoint = URI.create("http://127.0.0.1:1")
        storage.accessKeyId = "synthetic-access"
        storage.secretAccessKey = "synthetic-secret"
        return storage
    }

    @Configuration(proxyBeanMethods = false)
    class WriterFixture {
        @Bean
        fun writeDataSource(): HikariDataSource {
            val source = mock(HikariDataSource::class.java)
            whenever(source.jdbcUrl).thenReturn("jdbc:postgresql://127.0.0.1:1/synthetic")
            whenever(source.maximumPoolSize).thenReturn(2)
            return source
        }

        @Bean
        fun writeJdbc(source: HikariDataSource): JdbcTemplate = JdbcTemplate(source)
    }
}
