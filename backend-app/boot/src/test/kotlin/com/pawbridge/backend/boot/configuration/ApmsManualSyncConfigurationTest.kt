package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
    fun givenManualFlagOnly__whenComposed__thenControllerUsesSharedRunnerWithoutSchedule() {
        provider.withPropertyValues("pawbridge.apms-manual.enabled=true").run { application ->
            assertThat(application).hasNotFailed()
                .hasSingleBean(ApmsBatchController::class.java)
                .hasSingleBean(ApmsBatchRunner::class.java)
                .hasBean("apmsAnimalSyncJob")
                .doesNotHaveBean(ApmsCollectionSchedule::class.java)
            assertThat(ReflectionTestUtils.getField(application.getBean(ApmsBatchController::class.java), "apmsBatchRunner"))
                .isSameAs(application.getBean(ApmsBatchRunner::class.java))
            verify(application.getBean(HikariDataSource::class.java), never()).getConnection()
        }
    }

    @Test
    fun givenScheduleFlagOnly__whenComposed__thenExistingScheduleStaysWithoutManualController() {
        provider.withPropertyValues("apms.batch.schedule-enabled=true").run { application ->
            assertThat(application).hasNotFailed()
                .hasSingleBean(ApmsCollectionSchedule::class.java)
                .doesNotHaveBean(ApmsBatchController::class.java)
        }
    }

    @Test
    fun givenBothFlags__whenComposed__thenBothEntryPointsShareOneRunner() {
        provider.withPropertyValues("apms.batch.schedule-enabled=true", "pawbridge.apms-manual.enabled=TRUE")
            .run { application ->
                assertThat(application).hasNotFailed()
                    .hasSingleBean(ApmsBatchRunner::class.java)
                    .hasSingleBean(ApmsCollectionSchedule::class.java)
                    .hasSingleBean(ApmsBatchController::class.java)
            }
    }

    @Test
    fun givenManualFlagOffOrAlias__whenContextStarts__thenNoApmsDependenciesAreRequired() {
        for (value in arrayOf("false", "on", "yes")) {
            context.withPropertyValues("pawbridge.apms-manual.enabled=$value", "apms.api.base-url=invalid")
                .run { application ->
                    assertThat(application).hasNotFailed()
                        .doesNotHaveBean(ApmsBatchController::class.java)
                        .doesNotHaveBean(ApmsBatchRunner::class.java)
                        .doesNotHaveBean(Job::class.java)
                        .doesNotHaveBean(ApmsScheduleSettings::class.java)
                }
        }
    }

    @Test
    fun givenManualFlagWithoutProviderSettings__whenComposed__thenFailLikeTheSchedule() {
        context.withUserConfiguration(AnimalApmsCollectionConfigurationTest.WriterFixture::class.java)
            .withPropertyValues("pawbridge.apms-manual.enabled=true", "apms.api.base-url=https://provider.example.test")
            .run { application ->
                assertThat(application.startupFailure).hasRootCauseMessage("APMS service key is required")
            }
    }
}
