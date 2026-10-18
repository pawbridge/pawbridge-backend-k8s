package com.pawbridge.userservice.scheduler;

import com.pawbridge.userservice.scheduler.OutboxCleanupScheduler;
import com.pawbridge.userservice.repository.OutboxEventRepository;
import com.pawbridge.userservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeScheduleControlTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OutboxCleanupScheduler.class)
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
            .withBean(ProcessedEventRepository.class, () -> mock(ProcessedEventRepository.class));

    @Test void disabledScheduleDoesNotRegisterTheWorker() {
        context.withPropertyValues("pawbridge.jobs.cleanup-enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(OutboxCleanupScheduler.class));
    }

    @Test void defaultConfigurationKeepsExistingBehavior() {
        context.run(c -> assertThat(c).hasSingleBean(OutboxCleanupScheduler.class));
    }
}
