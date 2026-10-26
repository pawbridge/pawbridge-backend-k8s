package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.PawBridgeApplication
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Import
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource

class AnimalSearchProjectionConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["missing", "false", "on", "yes", "backend", "analyzed"])
    fun `입력이 빠졌거나 문자 그대로 true가 아닐 때__조립하면__작업을 만들거나 연결을 빌리지 않는다`(missing: String) {
        // given
        val source: DataSource = mockk<DataSource>()

        excludeRecords { source.hashCode() }

        var runner: WebApplicationContextRunner = runner(source)
        if (missing == "backend") {
            runner = runner.withPropertyValues(ENABLED, ANALYZED, "pawbridge.animal-query.backend=elasticsearch")
        } else if (missing == "analyzed") {
            runner = runner.withPropertyValues(ENABLED, BACKEND, "pawbridge.animal-query.analyzed-text=false")
        } else if (missing == "missing") {
            runner = runner.withPropertyValues(BACKEND, ANALYZED)
        } else {
            runner = runner.withPropertyValues(
                BACKEND,
                ANALYZED,
                "pawbridge.animal-query.projection-schedule-enabled=$missing",
            )
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(PostgresqlSearchProjector::class.java)
                .doesNotHaveBean(AnimalSearchProjectionJobs::class.java)
                .doesNotHaveBean("animalSearchProjectionScheduler")
            confirmVerified(source)
        }
    }

    @Test
    fun `웹이 아닌 프로세스일 때__조립하면__투영 작업을 예약하지 않는다`() {
        // given
        val runner = ApplicationContextRunner().withUserConfiguration(AnimalSearchProjectionConfiguration::class.java)
            .withPropertyValues(ENABLED, BACKEND, ANALYZED)

        // when
        runner
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(PostgresqlSearchProjector::class.java)
                    .doesNotHaveBean(AnimalSearchProjectionJobs::class.java)
            }
    }

    @Test
    fun `서블릿 컨텍스트에서 켜졌을 때__조립하면__기존 자원과 이름 있는 스케줄러 하나를 쓴다`() {
        // given
        val source: DataSource = mockk<DataSource>()

        every { source.connection } throws SQLException("owned-test-unavailable")

        // when
        runner(source).withPropertyValues(ENABLED, BACKEND, ANALYZED).run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(PostgresqlSearchProjector::class.java)
                .hasSingleBean(AnimalSearchProjectionJobs::class.java)
                .hasSingleBean(KoreanSearchAnalyzer::class.java)
            assertThat(context.getBean(KoreanSearchAnalyzer::class.java))
                .isSameAs(context.getBean("animalDiscoveryAnalyzer"))
            assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(JdbcTransactionManager::class.java)).hasSize(1)
            val scheduler = context.getBean("animalSearchProjectionScheduler", ThreadPoolTaskScheduler::class.java)
            assertThat(scheduler.threadNamePrefix).isEqualTo("animal-search-projection-")
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
        }
    }

    @Test
    fun `기능 구성과 투영 구성을 함께 import할 때__조립하면__실제 분석기 하나를 공유한다`() {
        // given
        val source: DataSource = mockk<DataSource>()

        every { source.connection } throws SQLException("owned-test-unavailable")

        // when
        runner(source).withUserConfiguration(AnimalSearchConfiguration::class.java)
            .withPropertyValues(ENABLED, BACKEND, ANALYZED, "pawbridge.animal-commands.enabled=true")
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .hasSingleBean(KoreanSearchAnalyzer::class.java)
                    .hasSingleBean(PostgresqlSearchProjector::class.java)
                assertThat(context.getBean(KoreanSearchAnalyzer::class.java))
                    .isSameAs(context.getBean("animalDiscoveryAnalyzer"))
            }
    }

    @Test
    fun `다른 기록 매니저가 주어졌을 때__투영기를 만들면__연결을 빌리기 전에 거절한다`() {
        // given
        val source: DataSource = mockk<DataSource>()
        val other: DataSource = mockk<DataSource>()
        val analyzer = mockk<KoreanSearchAnalyzer>()

        // when
        val creation = {
            AnimalSearchProjectionConfiguration()
                .animalSearchProjector(source, JdbcTransactionManager(other), analyzer)
        }

        // then
        assertThatIllegalArgumentException().isThrownBy { creation() }
        confirmVerified(source, other)
    }

    @Test
    fun `스케줄러가 주어졌을 때__종료하면__작업자는 데몬이고 지연 작업은 취소된다`() {
        // given
        val scheduler = AnimalSearchProjectionConfiguration().animalSearchProjectionScheduler()
        scheduler.initialize()

        // when, then
        try {
            assertThat(scheduler.submit(Callable { Thread.currentThread().isDaemon }).get(2, TimeUnit.SECONDS)).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.continueExistingPeriodicTasksAfterShutdownPolicy).isFalse()
            assertThat(scheduler.scheduledThreadPoolExecutor.executeExistingDelayedTasksAfterShutdownPolicy).isFalse()
            val delayed = scheduler.scheduleWithFixedDelay(
                Runnable { throw AssertionError("must not start") },
                Instant.now().plusSeconds(60),
                Duration.ofSeconds(60),
            )
            scheduler.shutdown()
            assertThat(delayed!!.isCancelled).isTrue()
            assertThat(scheduler.scheduledThreadPoolExecutor.isShutdown).isTrue()
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `메인 import가 주어졌을 때__유지보수를 추가해도__기존 아카이브와 수집 import를 유지한다`() {
        // given
        val application = PawBridgeApplication::class.java

        // when
        val imports = application.getAnnotation(Import::class.java).value.map { it.java }

        // then
        assertThat(imports)
            .contains(
                AnimalSearchProjectionConfiguration::class.java,
                AnimalPhotoArchiveConfiguration::class.java,
                AnimalApmsCollectionConfiguration::class.java,
                AnimalTravelCollectionConfiguration::class.java,
                AnimalGalleryFeedConfiguration::class.java,
                ShelterCommandConfiguration::class.java,
            )
    }

    private fun runner(source: DataSource): WebApplicationContextRunner {
        return WebApplicationContextRunner().withUserConfiguration(AnimalSearchProjectionConfiguration::class.java)
            .withBean("writeDataSource", DataSource::class.java, Supplier { source })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { JdbcTransactionManager(source) },
            )
    }

    companion object {
        private const val ENABLED = "pawbridge.animal-query.projection-schedule-enabled=true"
        private const val BACKEND = "pawbridge.animal-query.backend=postgresql"
        private const val ANALYZED = "pawbridge.animal-query.analyzed-text=true"
    }
}
