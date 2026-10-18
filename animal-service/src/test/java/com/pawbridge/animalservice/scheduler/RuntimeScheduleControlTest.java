package com.pawbridge.animalservice.scheduler;

import com.pawbridge.animalservice.scheduler.OutboxCleanupScheduler;
import com.pawbridge.animalservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeScheduleControlTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OutboxCleanupScheduler.class)
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class));

    @Test void disabledScheduleDoesNotRegisterTheWorker() {
        context.withPropertyValues("pawbridge.jobs.cleanup-enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(OutboxCleanupScheduler.class));
    }

    @Test void defaultConfigurationKeepsExistingBehavior() {
        context.run(c -> assertThat(c).hasSingleBean(OutboxCleanupScheduler.class));
    }
}
