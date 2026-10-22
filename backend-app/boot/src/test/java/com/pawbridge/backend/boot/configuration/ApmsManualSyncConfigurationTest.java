package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class ApmsManualSyncConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AnimalApmsCollectionConfiguration.class);
    private final ApplicationContextRunner provider = context
            .withUserConfiguration(AnimalApmsCollectionConfigurationTest.WriterFixture.class)
            .withPropertyValues("tourapi.enabled=false", "tourapi.schedule-enabled=false",
                    "apms.api.base-url=https://provider.example.test/openapi", "apms.api.service-key=fixture-key",
                    "apms.batch.collection-cron=0 0 0 1 1 *");

    @Test void givenManualFlagOnly__whenComposed__thenControllerUsesSharedRunnerWithoutSchedule() {
        provider.withPropertyValues("pawbridge.apms-manual.enabled=true").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(ApmsBatchController.class)
                    .hasSingleBean(ApmsBatchRunner.class).hasBean("apmsAnimalSyncJob")
                    .doesNotHaveBean(ApmsCollectionSchedule.class);
            assertThat(ReflectionTestUtils.getField(application.getBean(ApmsBatchController.class), "apmsBatchRunner"))
                    .isSameAs(application.getBean(ApmsBatchRunner.class));
            try { verify(application.getBean(HikariDataSource.class), never()).getConnection(); }
            catch (java.sql.SQLException impossible) { throw new AssertionError(impossible); }
        });
    }

    @Test void givenScheduleFlagOnly__whenComposed__thenExistingScheduleStaysWithoutManualController() {
        provider.withPropertyValues("apms.batch.schedule-enabled=true").run(application -> assertThat(application)
                .hasNotFailed().hasSingleBean(ApmsCollectionSchedule.class).doesNotHaveBean(ApmsBatchController.class));
    }

    @Test void givenBothFlags__whenComposed__thenBothEntryPointsShareOneRunner() {
        provider.withPropertyValues("apms.batch.schedule-enabled=true", "pawbridge.apms-manual.enabled=TRUE")
                .run(application -> assertThat(application).hasNotFailed().hasSingleBean(ApmsBatchRunner.class)
                        .hasSingleBean(ApmsCollectionSchedule.class).hasSingleBean(ApmsBatchController.class));
    }

    @Test void givenManualFlagOffOrAlias__whenContextStarts__thenNoApmsDependenciesAreRequired() {
        for (String value : new String[] {"false", "on", "yes"}) {
            context.withPropertyValues("pawbridge.apms-manual.enabled=" + value, "apms.api.base-url=invalid")
                    .run(application -> assertThat(application).hasNotFailed()
                            .doesNotHaveBean(ApmsBatchController.class).doesNotHaveBean(ApmsBatchRunner.class)
                            .doesNotHaveBean(Job.class).doesNotHaveBean(ApmsScheduleSettings.class));
        }
    }

    @Test void givenManualFlagWithoutProviderSettings__whenComposed__thenFailLikeTheSchedule() {
        context.withUserConfiguration(AnimalApmsCollectionConfigurationTest.WriterFixture.class)
                .withPropertyValues("pawbridge.apms-manual.enabled=true", "apms.api.base-url=https://provider.example.test")
                .run(application -> assertThat(application.getStartupFailure())
                        .hasRootCauseMessage("APMS service key is required"));
    }
}
