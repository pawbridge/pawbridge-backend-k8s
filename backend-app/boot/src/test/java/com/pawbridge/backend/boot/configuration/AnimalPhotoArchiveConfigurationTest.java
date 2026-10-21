package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveHttpClient;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveObjectStorage;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveProperties;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore;
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveWorker;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

class AnimalPhotoArchiveConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AnimalPhotoArchiveConfiguration.class);

    @Test void givenActualMainAssembly__whenImportsAreRead__thenPhotoProducerIsRegistered() {
        assertThat(com.pawbridge.backend.PawBridgeApplication.class
                .getAnnotation(org.springframework.context.annotation.Import.class).value())
                .contains(AnimalPhotoArchiveConfiguration.class);
    }

    @Test void givenMissingFlag__whenContextStarts__thenNoWriterOrExternalClientsAreRequired() {
        context.withPropertyValues("apms-photo-archive.optimizer-url=invalid").run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(PhotoArchiveWorker.class)
                    .doesNotHaveBean(PhotoArchiveSchedule.class).doesNotHaveBean(PhotoArchiveHttpClient.class)
                    .doesNotHaveBean(PhotoArchiveObjectStorage.class).doesNotHaveBean(JpaTransactionManager.class);
        });
    }

    @Test void givenExplicitOff__whenContextStarts__thenNoArchiveBeansAreCreated() {
        context.withPropertyValues("apms-photo-archive.enabled=false").run(app ->
                assertThat(app).hasNotFailed().doesNotHaveBean(PhotoArchiveStore.class));
    }

    @Test void givenExplicitSettings__whenComposed__thenShareWriterAndCreateNoPublicS3ClientOrConnection() {
        enabled().run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(PhotoArchiveWorker.class)
                    .hasSingleBean(PhotoArchiveSchedule.class).hasSingleBean(HikariDataSource.class)
                    .hasSingleBean(JpaTransactionManager.class).doesNotHaveBean(S3Client.class)
                    .doesNotHaveBean("apmsAnimalSyncJob").doesNotHaveBean("petTravelCollectionJob");
            var manager = app.getBean("animalCommandJpaTransactionManager", JpaTransactionManager.class);
            var transactions = (TransactionTemplate) ReflectionTestUtils.getField(app.getBean(PhotoArchiveStore.class), "transaction");
            assertThat(transactions.getTransactionManager()).isSameAs(manager);
            assertThat(transactions.getTimeout()).isEqualTo(15);
            assertThat(app.getBean(PhotoArchiveProperties.class).getBucket()).isEqualTo("pawbridge-animal-originals");
            assertThat(app.getBean(PhotoArchiveHttpClient.class)).isInstanceOf(AutoCloseable.class);
            try { verify(app.getBean(HikariDataSource.class), never()).getConnection(); }
            catch (java.sql.SQLException impossible) { throw new AssertionError(impossible); }
        });
    }

    @Test void givenDifferentBucket__whenEnabled__thenRejectBeforeCreatingArchiveClients() {
        enabled().withPropertyValues("apms-photo-archive.bucket=some-other-bucket").run(app ->
                assertThat(app.getStartupFailure()).hasRootCauseMessage("Photo archive must use the existing gallery bucket"));
    }

    @Test void givenOnlyPublicUploadCredentials__whenEnabled__thenDedicatedCredentialsRemainRequired() {
        enabled().withPropertyValues("apms-photo-archive.storage.access-key-id=",
                "spring.cloud.aws.credentials.access-key=public-fixture",
                "spring.cloud.aws.credentials.secret-key=public-fixture").run(app ->
                assertThat(app.getStartupFailure()).hasRootCauseMessage("Dedicated APMS photo storage configuration is required"));
    }

    @Test void givenMixedWriter__whenCreatingStore__thenFailBeforeBorrowingConnection() {
        var source = mock(HikariDataSource.class);
        when(source.getJdbcUrl()).thenReturn("jdbc:postgresql://127.0.0.1:1/synthetic");
        var manager = mock(JpaTransactionManager.class);
        when(manager.getDataSource()).thenReturn(source);
        assertThatThrownBy(() -> new AnimalPhotoArchiveConfiguration().apmsPhotoArchiveStore(
                new JdbcTemplate(mock(HikariDataSource.class)), source, manager, properties(), storage()))
                .hasMessage("Photo archive requires the same existing PostgreSQL writer");
    }

    private ApplicationContextRunner enabled() {
        return context.withUserConfiguration(WriterFixture.class).withPropertyValues(
                "apms-photo-archive.enabled=true", "apms-photo-archive.bucket=pawbridge-animal-originals",
                "apms-photo-archive.optimizer-url=http://127.0.0.1:1/optimize",
                "apms-photo-archive.internal-api-key=synthetic-key",
                "apms-photo-archive.storage.endpoint=http://127.0.0.1:1",
                "apms-photo-archive.storage.access-key-id=synthetic-access",
                "apms-photo-archive.storage.secret-access-key=synthetic-secret",
                "apms-photo-archive.initial-delay-ms=3600000");
    }

    private static PhotoArchiveProperties properties() {
        var properties = new PhotoArchiveProperties();
        properties.setBucket("pawbridge-animal-originals");
        properties.setOptimizerUrl(java.net.URI.create("http://127.0.0.1:1/optimize"));
        properties.setInternalApiKey("synthetic-key");
        return properties;
    }

    private static PhotoArchiveStorageProperties storage() {
        var storage = new PhotoArchiveStorageProperties();
        storage.setEndpoint(java.net.URI.create("http://127.0.0.1:1"));
        storage.setAccessKeyId("synthetic-access");
        storage.setSecretAccessKey("synthetic-secret");
        return storage;
    }

    @Configuration(proxyBeanMethods = false)
    static class WriterFixture {
        @Bean HikariDataSource writeDataSource() {
            var source = mock(HikariDataSource.class);
            when(source.getJdbcUrl()).thenReturn("jdbc:postgresql://127.0.0.1:1/synthetic");
            when(source.getMaximumPoolSize()).thenReturn(2);
            return source;
        }
        @Bean JdbcTemplate writeJdbc(HikariDataSource source) { return new JdbcTemplate(source); }
    }
}
