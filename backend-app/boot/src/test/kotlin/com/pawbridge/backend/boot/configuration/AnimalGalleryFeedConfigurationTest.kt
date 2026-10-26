package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `플래그가 true가 아닐 때__조립하면__키 풀이나 서명기나 파일시스템을 요구하지 않는다`(flag: String) {
        // given
        var context = runner()
        if (flag != "absent") {
            context = context.withPropertyValues("lost-gallery-feed.enabled=$flag")
        }

        // when
        context.run { app ->
            // then
            assertThat(app).hasNotFailed()
                .doesNotHaveBean(LostGalleryFeed::class.java)
                .doesNotHaveBean(LostGallerySnapshots::class.java)
                .doesNotHaveBean(S3Presigner::class.java)
                .doesNotHaveBean(DataSource::class.java)
        }
    }

    @Test
    fun `키가 없을 때__켜면__리더와 서명기와 디렉터리 전에 거절한다`() {
        // given
        val runner = runner().withPropertyValues("lost-gallery-feed.enabled=true")

        // when
        runner.run { app ->
            // then
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
    fun `전용이 아닌 엔드포인트가 주어졌을 때__설정하면__에코나 원인 없이 거절한다`(address: String) {
        // given, when, then
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
    fun `빈 값이거나 상대 경로이거나 정규화되지 않은 디렉터리일 때__설정하면__파일시스템 변경 전에 거절한다`() {
        // given
        val directories = listOf(
            "", "   ", "snapshots", "./snapshots", "/",
            Path.of("").toAbsolutePath().normalize().toString(),
            root.resolve("child/../snapshots").toString(),
        )

        // when, then
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
    fun `명시한 오프라인 입력이 주어졌을 때__조립하면__리더를 재사용하고 정확한 GET에 서명하며 디렉터리 소유권을 해제한다`() {
        // given
        val source: DataSource = mockk<DataSource>()

        excludeRecords { source.hashCode() }

        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 3
        val manager = JdbcTransactionManager(source)
        manager.isEnforceReadOnly = true
        val directory: Path = root.resolve("snapshots")
        val mapper = ObjectMapper()

        // when
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
                // then
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
                confirmVerified(source)
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
        confirmVerified(source)
    }

    @Test
    fun `서로 다른 리더 바인딩이 주어졌을 때__피드를 만들면__연결이나 서명 전에 거절한다`() {
        // given
        val first = mockk<DataSource>()
        val other = mockk<DataSource>()
        val signer = mockk<S3Presigner>()
        val config = AnimalGalleryFeedConfiguration(
            "synthetic-internal", "https://synthetic.r2.cloudflarestorage.com",
            "synthetic-access", "synthetic-secret", root.toString(),
        )

        // when, then
        assertThatThrownBy {
            config.animalGalleryFeed(JdbcTemplate(first), first, JdbcTransactionManager(other), ObjectMapper(), signer)
        }.hasMessage("Gallery reads must use the existing reader DataSource and transaction manager")
        confirmVerified(first, other, signer)
    }
}
