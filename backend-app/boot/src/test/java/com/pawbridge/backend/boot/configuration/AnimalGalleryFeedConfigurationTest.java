package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class AnimalGalleryFeedConfigurationTest {
    @TempDir Path root;
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalGalleryFeedConfiguration.class);
    }

    @ParameterizedTest @ValueSource(strings={"absent","false","on","yes","1","unknown"})
    void givenNonTrueFlag_whenAssembling_thenNoKeyPoolSignerOrFilesystemIsRequired(String flag) {
        var context=runner(); if(!flag.equals("absent"))context=context.withPropertyValues("lost-gallery-feed.enabled="+flag);
        context.run(app->assertThat(app).hasNotFailed().doesNotHaveBean(LostGalleryFeed.class)
                .doesNotHaveBean(LostGallerySnapshots.class).doesNotHaveBean(S3Presigner.class).doesNotHaveBean(DataSource.class));
    }

    @Test void givenMissingKey_whenEnabled_thenRejectBeforeReaderSignerOrDirectory() {
        runner().withPropertyValues("lost-gallery-feed.enabled=true").run(app->{
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage("Gallery feed authentication is required");
            assertThat(root.resolve("snapshots")).doesNotExist();
        });
    }

    @ParameterizedTest @ValueSource(strings={"http://synthetic.r2.cloudflarestorage.com", "https://example.invalid", "https://synthetic.r2.cloudflarestorage.com/?private=synthetic", "https://user:synthetic@synthetic.r2.cloudflarestorage.com", "https://synthetic.r2.cloudflarestorage.com/other", "not a URI"})
    void givenNonDedicatedEndpoint_whenConfiguring_thenRejectWithoutEchoOrCause(String address) {
        assertThatThrownBy(()->new AnimalGalleryFeedConfiguration("synthetic-internal",address,"synthetic-access","synthetic-secret",root.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Dedicated R2 signing configuration is required").hasNoCause();
    }

    @Test @SuppressWarnings("unchecked")
    void givenExplicitOfflineInputs_whenAssembling_thenReuseReaderSignExactGetAndReleaseDirectoryOwnership() throws Exception {
        DataSource source=mock(DataSource.class);var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);
        var manager=new JdbcTransactionManager(source);manager.setEnforceReadOnly(true);
        Path directory=root.resolve("snapshots");var mapper=new ObjectMapper();
        runner().withBean("dataSource",DataSource.class,()->source).withBean("readJdbc",JdbcTemplate.class,()->jdbc)
                .withBean("transactionManager",JdbcTransactionManager.class,()->manager).withBean(ObjectMapper.class,()->mapper)
                .withPropertyValues("lost-gallery-feed.enabled=TrUe","lost-gallery-feed.internal-api-key=synthetic-internal",
                        "lost-gallery-feed.storage.endpoint=https://synthetic.r2.cloudflarestorage.com",
                        "lost-gallery-feed.storage.access-key-id=synthetic-access","lost-gallery-feed.storage.secret-access-key=synthetic-secret",
                        "lost-gallery-feed.snapshot-directory="+directory)
                .run(app->{
                    assertThat(app).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(JdbcTransactionManager.class)
                            .hasSingleBean(S3Presigner.class).hasSingleBean(LostGalleryFeed.class).hasSingleBean(LostGallerySnapshots.class)
                            .hasSingleBean(LostGalleryFeedController.class).hasSingleBean(LostGalleryPagesController.class)
                            .doesNotHaveBean("writeDataSource");
                    var feed=app.getBean(LostGalleryFeed.class);
                    assertThat(ReflectionTestUtils.getField(feed,"jdbc")).isSameAs(jdbc);
                    var reads=(TransactionTemplate)ReflectionTestUtils.getField(feed,"reads");
                    var stream=(TransactionTemplate)ReflectionTestUtils.getField(feed,"streamReads");
                    for(var tx:java.util.List.of(reads,stream)) {
                        assertThat(tx.getTransactionManager()).isSameAs(manager);assertThat(tx.isReadOnly()).isTrue();assertThat(tx.getTimeout()).isEqualTo(120);
                    }
                    assertThat(stream.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(reads.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                    assertThat(jdbc.getQueryTimeout()).isEqualTo(3);
                    var signer=(Function<String,String>)ReflectionTestUtils.getField(feed,"signer");
                    var signed=java.net.URI.create(signer.apply("apms/photos/"+"a".repeat(64)+".jpg"));
                    assertThat(signed.getHost()).isEqualTo("synthetic.r2.cloudflarestorage.com");
                    assertThat(signed.getPath()).isEqualTo("/pawbridge-animal-originals/apms/photos/"+"a".repeat(64)+".jpg");
                    assertThat(signed.getQuery()).contains("X-Amz-Expires=3600").doesNotContain("synthetic-secret");
                    assertThatThrownBy(()->new LostGallerySnapshots(mapper,consumer->{},key->key,directory,Clock.systemUTC()))
                            .isInstanceOf(RuntimeException.class);
                    verifyNoInteractions(source);
                });
        try(var reopened=new LostGallerySnapshots(mapper,consumer->{},key->key,directory,Clock.systemUTC())) {
            assertThat(directory.resolve("owner.lock")).isRegularFile();
        }
        verifyNoInteractions(source);
    }

    @Test void givenDifferentReaderBindings_whenCreatingFeed_thenRejectBeforeConnectionOrSigning() {
        var first=mock(DataSource.class);var other=mock(DataSource.class);var signer=mock(S3Presigner.class);
        var config=new AnimalGalleryFeedConfiguration("synthetic-internal","https://synthetic.r2.cloudflarestorage.com",
                "synthetic-access","synthetic-secret",root.toString());
        assertThatThrownBy(()->config.animalGalleryFeed(new JdbcTemplate(first),first,new JdbcTransactionManager(other),new ObjectMapper(),signer))
                .hasMessage("Gallery reads must use the existing reader DataSource and transaction manager");
        verifyNoInteractions(first,other,signer);
    }
}
