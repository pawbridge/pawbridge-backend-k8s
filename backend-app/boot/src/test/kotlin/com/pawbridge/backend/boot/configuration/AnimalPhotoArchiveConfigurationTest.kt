package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveHttpClient
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveObjectStorage
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun `플래그가 없을 때__컨텍스트가 시작되면__기록기나 외부 클라이언트가 필요하지 않다`() {
        // given
        val runner = context.withPropertyValues("apms-photo-archive.optimizer-url=invalid")

        // when
        runner.run { app ->
            // then
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(PhotoArchiveWorker::class.java)
                .doesNotHaveBean(PhotoArchiveSchedule::class.java)
                .doesNotHaveBean(PhotoArchiveHttpClient::class.java)
                .doesNotHaveBean(PhotoArchiveObjectStorage::class.java)
                .doesNotHaveBean(JpaTransactionManager::class.java)
        }
    }

    @Test
    fun `명시적으로 꺼져 있을 때__컨텍스트가 시작되면__아카이브 빈을 만들지 않는다`() {
        // given
        val runner = context.withPropertyValues("apms-photo-archive.enabled=false")

        // when
        runner.run { app ->
            // then
            assertThat(app).hasNotFailed().doesNotHaveBean(PhotoArchiveStore::class.java)
        }
    }

    @Test
    fun `명시 설정이 주어졌을 때__조립하면__기록 연결을 공유하고 공개 S3 클라이언트나 연결을 만들지 않는다`() {
        // given
        val runner = enabled()

        // when
        runner.run { app ->
            // then
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
            verify(exactly = 0) { app.getBean(HikariDataSource::class.java).connection }
        }
    }

    @Test
    fun `다른 버킷이 주어졌을 때__켜면__아카이브 클라이언트를 만들기 전에 거절한다`() {
        // given
        val runner = enabled().withPropertyValues("apms-photo-archive.bucket=some-other-bucket")

        // when
        runner.run { app ->
            // then
            assertThat(app.startupFailure).hasRootCauseMessage("Photo archive must use the existing gallery bucket")
        }
    }

    @Test
    fun `공개 업로드 자격 증명만 있을 때__켜면__전용 자격 증명이 계속 필요하다`() {
        // given
        val runner = enabled().withPropertyValues(
            "apms-photo-archive.storage.access-key-id=",
            "spring.cloud.aws.credentials.access-key=public-fixture",
            "spring.cloud.aws.credentials.secret-key=public-fixture",
        )

        // when
        runner.run { app ->
            // then
            assertThat(app.startupFailure).hasRootCauseMessage("Dedicated APMS photo storage configuration is required")
        }
    }

    @Test
    fun `섞인 기록 연결이 주어졌을 때__저장소를 만들면__연결을 빌리기 전에 실패한다`() {
        // given
        val source = mockk<HikariDataSource>()
        val manager = mockk<JpaTransactionManager>()
        val otherSource = mockk<HikariDataSource>()

        every { source.jdbcUrl } returns "jdbc:postgresql://127.0.0.1:1/synthetic"
        every { manager.dataSource } returns source

        // when, then
        assertThatThrownBy {
            AnimalPhotoArchiveConfiguration().apmsPhotoArchiveStore(
                JdbcTemplate(otherSource), source, manager, properties(), storage(),
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
            val source = mockk<HikariDataSource>()
            every { source.jdbcUrl } returns "jdbc:postgresql://127.0.0.1:1/synthetic"
            every { source.maximumPoolSize } returns 2
            justRun { source.close() }
            return source
        }

        @Bean
        fun writeJdbc(source: HikariDataSource): JdbcTemplate = JdbcTemplate(source)
    }
}
