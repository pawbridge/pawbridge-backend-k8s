package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController
import com.zaxxer.hikari.HikariDataSource
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.Job
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils

class ApmsManualSyncConfigurationTest {
    private val context = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
        .withUserConfiguration(AnimalApmsCollectionConfiguration::class.java)
    private val provider = context
        .withUserConfiguration(AnimalApmsCollectionConfigurationTest.WriterFixture::class.java)
        .withPropertyValues(
            "tourapi.enabled=false", "tourapi.schedule-enabled=false",
            "apms.api.base-url=https://provider.example.test/openapi", "apms.api.service-key=fixture-key",
            "apms.batch.collection-cron=0 0 0 1 1 *",
        )

    @Test
    fun `수동 플래그만 켰을 때__조립하면__스케줄 없이 컨트롤러가 공유 러너를 쓴다`() {
        // given
        val runner = provider.withPropertyValues("pawbridge.apms-manual.enabled=true")

        // when
        runner.run { application ->
            // then
            assertThat(application).hasNotFailed()
                .hasSingleBean(ApmsBatchController::class.java)
                .hasSingleBean(ApmsBatchRunner::class.java)
                .hasBean("apmsAnimalSyncJob")
                .doesNotHaveBean(ApmsCollectionSchedule::class.java)
            assertThat(ReflectionTestUtils.getField(application.getBean(ApmsBatchController::class.java), "apmsBatchRunner"))
                .isSameAs(application.getBean(ApmsBatchRunner::class.java))
            verify(exactly = 0) { application.getBean(HikariDataSource::class.java).connection }
        }
    }

    @Test
    fun `스케줄 플래그만 켰을 때__조립하면__수동 컨트롤러 없이 기존 스케줄이 유지된다`() {
        // given
        val runner = provider.withPropertyValues("apms.batch.schedule-enabled=true")

        // when
        runner.run { application ->
            // then
            assertThat(application).hasNotFailed()
                .hasSingleBean(ApmsCollectionSchedule::class.java)
                .doesNotHaveBean(ApmsBatchController::class.java)
        }
    }

    @Test
    fun `두 플래그를 모두 켰을 때__조립하면__두 진입점이 러너 하나를 공유한다`() {
        // given
        val runner = provider.withPropertyValues("apms.batch.schedule-enabled=true", "pawbridge.apms-manual.enabled=TRUE")

        // when
        runner
            .run { application ->
                // then
                assertThat(application).hasNotFailed()
                    .hasSingleBean(ApmsBatchRunner::class.java)
                    .hasSingleBean(ApmsCollectionSchedule::class.java)
                    .hasSingleBean(ApmsBatchController::class.java)
            }
    }

    @Test
    fun `수동 플래그가 꺼졌거나 별칭일 때__컨텍스트가 시작되면__APMS 의존 빈이 필요하지 않다`() {
        for (value in arrayOf("false", "on", "yes")) {
            // given
            val runner = context.withPropertyValues("pawbridge.apms-manual.enabled=$value", "apms.api.base-url=invalid")

            // when
            runner
                .run { application ->
                    // then
                    assertThat(application).hasNotFailed()
                        .doesNotHaveBean(ApmsBatchController::class.java)
                        .doesNotHaveBean(ApmsBatchRunner::class.java)
                        .doesNotHaveBean(Job::class.java)
                        .doesNotHaveBean(ApmsScheduleSettings::class.java)
                }
        }
    }

    @Test
    fun `제공자 설정 없이 수동 플래그를 켰을 때__조립하면__스케줄처럼 실패한다`() {
        // given
        val runner = context.withUserConfiguration(AnimalApmsCollectionConfigurationTest.WriterFixture::class.java)
            .withPropertyValues("pawbridge.apms-manual.enabled=true", "apms.api.base-url=https://provider.example.test")

        // when
        runner
            .run { application ->
                // then
                assertThat(application.startupFailure).hasRootCauseMessage("APMS service key is required")
            }
    }
}
