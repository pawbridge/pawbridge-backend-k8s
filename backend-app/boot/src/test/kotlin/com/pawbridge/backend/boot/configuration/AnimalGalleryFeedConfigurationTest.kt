package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalGalleryFeedConfigurationTest {
    @TempDir
    lateinit var root: Path

    fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(AnimalGalleryFeedConfiguration::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes", "1", "unknown"])
    fun givenNonTrueFlag_whenAssembling_thenNoKeyPoolSignerOrFilesystemIsRequired(flag: String) {
        var context = runner()
        if (flag != "absent") {
            context = context.withPropertyValues("lost-gallery-feed.enabled=$flag")
        }
        context.run { app ->
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(LostGalleryFeed::class.java)
                .doesNotHaveBean(LostGallerySnapshots::class.java)
                .doesNotHaveBean(S3Presigner::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @Test
    fun givenMissingKey_whenEnabled_thenRejectBeforeReaderSignerOrDirectory() {
        runner().withPropertyValues("lost-gallery-feed.enabled=true").run { app ->
            assertThat(app).hasFailed()
            assertThat(app.startupFailure).hasRootCauseMessage("Gallery feed authentication is required")
            assertThat(root.resolve("snapshots")).doesNotExist()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://synthetic.r2.cloudflarestorage.com",
            "https://example.invalid",
            "https://synthetic.r2.cloudflarestorage.com/?private=synthetic",
            "https://user:synthetic@synthetic.r2.cloudflarestorage.com",
            "https://synthetic.r2.cloudflarestorage.com/other",
            "not a URI",
        ],
    )
    fun givenNonDedicatedEndpoint_whenConfiguring_thenRejectWithoutEchoOrCause(address: String) {
        assertThatThrownBy {
            AnimalGalleryFeedConfiguration(
                "synthetic-internal", address, "synthetic-access", "synthetic-secret", root.toString(),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Dedicated R2 signing configuration is required")
            .hasNoCause()
    }

    @Test
    fun givenBlankRelativeOrUnnormalizedDirectory_whenConfiguring_thenRejectBeforeFilesystemMutation() {
        val directories = listOf(
            "", "   ", "snapshots", "./snapshots", "/",
            Path.of("").toAbsolutePath().normalize().toString(),
            root.resolve("child/../snapshots").toString(),
        )
        for (directory in directories) {
            assertThatThrownBy {
                AnimalGalleryFeedConfiguration(
                    "synthetic-internal", "https://synthetic.r2.cloudflarestorage.com",
                    "synthetic-access", "synthetic-secret", directory,
                )
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("A local snapshot directory is required")
                .hasNoCause()
        }
        assertThatCode {
            AnimalGalleryFeedConfiguration(
                "synthetic-internal", "https://synthetic.r2.cloudflarestorage.com",
                "synthetic-access", "synthetic-secret", root.resolve("snapshots").toString(),
            )
        }.doesNotThrowAnyException()
        Files.list(root).use { entries -> assertThat(entries.toList()).isEmpty() }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun givenExplicitOfflineInputs_whenAssembling_thenReuseReaderSignExactGetAndReleaseDirectoryOwnership() {
        val source: DataSource = mock(DataSource::class.java)
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 3
        val manager = JdbcTransactionManager(source)
        manager.isEnforceReadOnly = true
        val directory: Path = root.resolve("snapshots")
        val mapper = ObjectMapper()
        runner().withBean("dataSource", DataSource::class.java, Supplier { source })
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("transactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean(ObjectMapper::class.java, Supplier { mapper })
            .withPropertyValues(
                "lost-gallery-feed.enabled=TrUe",
                "lost-gallery-feed.internal-api-key=synthetic-internal",
                "lost-gallery-feed.storage.endpoint=https://synthetic.r2.cloudflarestorage.com",
                "lost-gallery-feed.storage.access-key-id=synthetic-access",
                "lost-gallery-feed.storage.secret-access-key=synthetic-secret",
                "lost-gallery-feed.snapshot-directory=$directory",
            )
            .run { app ->
                assertThat(app).hasNotFailed()
                    .hasSingleBean(DataSource::class.java)
                    .hasSingleBean(JdbcTransactionManager::class.java)
                    .hasSingleBean(S3Presigner::class.java)
                    .hasSingleBean(LostGalleryFeed::class.java)
                    .hasSingleBean(LostGallerySnapshots::class.java)
                    .hasSingleBean(LostGalleryFeedController::class.java)
                    .hasSingleBean(LostGalleryPagesController::class.java)
                    .doesNotHaveBean("writeDataSource")
                val feed = app.getBean(LostGalleryFeed::class.java)
                assertThat(ReflectionTestUtils.getField(feed, "jdbc")).isSameAs(jdbc)
                val reads = ReflectionTestUtils.getField(feed, "reads") as TransactionTemplate
                val stream = ReflectionTestUtils.getField(feed, "streamReads") as TransactionTemplate
                for (tx in listOf(reads, stream)) {
                    assertThat(tx.transactionManager).isSameAs(manager)
                    assertThat(tx.isReadOnly).isTrue()
                    assertThat(tx.timeout).isEqualTo(120)
                }
                assertThat(stream.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                assertThat(reads.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                assertThat(jdbc.queryTimeout).isEqualTo(3)
                val signer = ReflectionTestUtils.getField(feed, "signer") as Function<String, String>
                val signed = URI.create(signer.apply("apms/photos/" + "a".repeat(64) + ".jpg"))
                assertThat(signed.host).isEqualTo("synthetic.r2.cloudflarestorage.com")
                assertThat(signed.path).isEqualTo("/pawbridge-animal-originals/apms/photos/" + "a".repeat(64) + ".jpg")
                assertThat(signed.query).contains("X-Amz-Expires=3600").doesNotContain("synthetic-secret")
                assertThatThrownBy {
                    LostGallerySnapshots(
                        mapper,
                        Consumer<Consumer<LostGallerySnapshots.Entry>> { },
                        Function<String, String> { key -> key },
                        directory,
                        Clock.systemUTC(),
                    )
                }.isInstanceOf(RuntimeException::class.java)
                verifyNoInteractions(source)
            }
        LostGallerySnapshots(
            mapper,
            Consumer<Consumer<LostGallerySnapshots.Entry>> { },
            Function<String, String> { key -> key },
            directory,
            Clock.systemUTC(),
        ).use {
            assertThat(directory.resolve("owner.lock")).isRegularFile()
        }
        verifyNoInteractions(source)
    }

    @Test
    fun givenDifferentReaderBindings_whenCreatingFeed_thenRejectBeforeConnectionOrSigning() {
        val first = mock(DataSource::class.java)
        val other = mock(DataSource::class.java)
        val signer = mock(S3Presigner::class.java)
        val config = AnimalGalleryFeedConfiguration(
            "synthetic-internal", "https://synthetic.r2.cloudflarestorage.com",
            "synthetic-access", "synthetic-secret", root.toString(),
        )
        assertThatThrownBy {
            config.animalGalleryFeed(JdbcTemplate(first), first, JdbcTransactionManager(other), ObjectMapper(), signer)
        }.hasMessage("Gallery reads must use the existing reader DataSource and transaction manager")
        verifyNoInteractions(first, other, signer)
    }
}
